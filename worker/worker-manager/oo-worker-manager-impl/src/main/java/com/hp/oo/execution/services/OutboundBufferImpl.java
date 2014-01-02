package com.hp.oo.execution.services;

import ch.lambdaj.group.Group;
import com.hp.oo.orchestrator.entities.Message;
import com.hp.oo.orchestrator.services.OrchestratorDispatcherService;
import org.apache.commons.lang.Validate;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static ch.lambdaj.Lambda.*;

@Component("outBuffer")
public class OutboundBufferImpl implements OutboundBuffer, WorkerRecoveryListener {
	private final Logger logger = Logger.getLogger(this.getClass());

	@Autowired
	private RetryTemplate retryTemplate;

	@Autowired
	private WorkerRecoveryManager recoveryManager;

	@Autowired
	private OrchestratorDispatcherService dispatcherService;

	private Queue<Message> buffer = new LinkedList<>();

	private final Lock lock = new ReentrantLock();
	private final Condition notEmpty = lock.newCondition();
	private final Condition notFull = lock.newCondition();

	private int currentWeight;

	private int maxBufferWeight = Integer.getInteger("out.buffer.max.buffer.weight", 30000);
	private int maxBulkWeight = Integer.getInteger("out.buffer.max.bulk.weight", 1500);
	private int retryAmount = Integer.getInteger("out.buffer.retry.number", 5);
	private long retryDelay = Long.getLong("out.buffer.retry.delay", 5000);

	@Override
	public void put(final Message... messages) {
		Validate.notEmpty(messages, "The array of messages is null or empty");
		lock.lock();
		try{
			while (currentWeight >= maxBufferWeight){
				logger.warn("Outbound buffer is full. Waiting...");
				notFull.await();
			}

			// in case of multiple messages create a single compound message
			// to make sure that it will be processed in a single transaction
			Message message = messages.length==1? messages[0]: new CompoundMessage(messages);

			if (!buffer.offer(message)){
				logger.error("Failed to put message into the outgoing buffer");
				throw new RuntimeException("Failed to put message into the outgoing buffer");
			}
			currentWeight += message.getWeight();
			if (logger.isTraceEnabled()) logger.trace(message.getClass().getSimpleName() + " added to the buffer. " + getStatus());
		} catch (InterruptedException ex) {
			logger.warn("Buffer put action was interrupted", ex);
		} finally {
			notEmpty.signalAll();
			lock.unlock();
		}
	}

	@Override
	public void drain() {
		lock.lock();

		Queue<Message> bufferToDrain;
		try{
			while (buffer.isEmpty()){
				if (logger.isDebugEnabled()) logger.debug("buffer is empty. Waiting to drain...");
				notEmpty.await();
			}

			if (logger.isDebugEnabled()) logger.debug("buffer is going to be drained. " + getStatus());

			bufferToDrain = buffer;
			buffer = new LinkedList<>();
			currentWeight = 0;
		} catch (InterruptedException e) {
			logger.warn("Drain outgoing buffer was interrupted while waiting for messages on the buffer");
			return;
		} finally{
			notFull.signalAll();
			lock.unlock();
		}

		drainInternal(bufferToDrain);
	}

	private void drainInternal(Collection<Message> bufferToDrain){
		List<Message> bulk = new LinkedList<>();
		int bulkWeight = 0;
		Map<String,AtomicInteger> logMap = new HashMap<>();
		try {
			for (Message message : bufferToDrain) {
				if (message.getClass().equals(CompoundMessage.class)){
					bulk.addAll(((CompoundMessage)message).asList());
				} else {
					bulk.add(message);
				}
				bulkWeight += message.getWeight();

				if (logger.isDebugEnabled()){
					if (logMap.get(message.getClass().getSimpleName()) == null) logMap.put(message.getClass().getSimpleName(), new AtomicInteger(1));
					else logMap.get(message.getClass().getSimpleName()).incrementAndGet();
				}

				if (bulkWeight > maxBulkWeight){
					if (logger.isDebugEnabled()) logger.debug("trying to drain bulk: " + logMap.toString() + ", W:" + bulkWeight);
					drainBulk(bulk);
					bulk.clear();
					bulkWeight = 0;
					logMap.clear();
				}
			}
			// drain the last bulk
			if (logger.isDebugEnabled()) logger.debug("trying to drain bulk: " + logMap.toString() + ", " + getStatus());
			drainBulk(bulk);
		} catch (Exception ex) {
			logger.error("Failed to drain buffer, invoking recovery", ex);
			recoveryManager.doRecovery();
		}
	}

	private List<Message> optimize(Collection<Message> messages){
		long t = System.currentTimeMillis();
		List<Message> result = new LinkedList<>();

		Group<Message> groups = group(messages, by(on(Message.class).getId()));
		for (Group<Message> group :groups.subgroups()){
			result.addAll(group.first().shrink(group.findAll()));
		}

		if (logger.isDebugEnabled()) logger.debug("bulk optimization result: " + messages.size() + " -> " + result.size() + " in " + (System.currentTimeMillis()-t) + " ms");

		return result;
	}

	private void drainBulk(List<Message> bulkToDrain){
		long t = System.currentTimeMillis();
		final List<Message> optimizedBulk = optimize(bulkToDrain);
		retryTemplate.retry(retryAmount, retryDelay, new RetryTemplate.RetryCallback() {
			@Override
			public void tryOnce() {
				dispatcherService.dispatch(optimizedBulk);
			}
		});
		if (logger.isDebugEnabled()) logger.debug("bulk was drained in " + (System.currentTimeMillis()-t) + " ms");
	}

	@Override
	public int getSize() {
		return buffer.size();
	}

	@Override
	public int getWeight() {
		return currentWeight;
	}

	@Override
	public String getStatus() {
		return "Buffer status: [W:" + currentWeight + '/' + maxBufferWeight + ",S:" + buffer.size() + "]";
	}

	@Override
	public void doRecovery() {
		if (logger.isDebugEnabled()) logger.debug("OutboundBuffer in recovery, clearing buffer");
		buffer.clear();
	}

	private class CompoundMessage implements Message{
		private Message[] messages;

		public CompoundMessage(Message[] messages){
			this.messages = messages.clone();
		}

		@Override
		public int getWeight() {
			int weight = 0;
			for (Message message : messages) weight += message.getWeight();
			return weight;
		}

		public List<Message> asList() {
			return Arrays.asList(messages);
		}

		@Override
		public String getId() {
			return null;
		}

		@Override
		public List<Message> shrink(List<Message> messages) {
			return messages; // do nothing
		}
	}
}