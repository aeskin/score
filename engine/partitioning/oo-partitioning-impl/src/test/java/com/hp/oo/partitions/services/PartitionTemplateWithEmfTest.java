package com.hp.oo.partitions.services;

import com.hp.oo.partitions.entities.PartitionGroup;
import com.hp.oo.partitions.repositories.PartitionGroupRepository;
import com.hp.score.engine.data.SimpleHiloIdentifierGenerator;
import liquibase.integration.spring.SpringLiquibase;
import org.apache.commons.dbcp.BasicDataSource;
import org.hibernate.ejb.HibernatePersistence;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.MethodInvokingFactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.transaction.TransactionConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;

import static org.fest.assertions.Assertions.assertThat;

/**
 * Date: 4/23/12
 *
 * @author Dima Rassin
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration
@Transactional
@TransactionConfiguration(defaultRollback = true)
public class PartitionTemplateWithEmfTest {
	private final static boolean SHOW_SQL = false;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private PartitionService service;

	private static final String tableName = "my_table";

	@Test
	@DirtiesContext
	public void startFirstTime() {
		applicationContext.getBean(tableName, PartitionTemplate.class);
		PartitionGroup partitionGroup = service.readPartitionGroup(tableName);
		assertThat(partitionGroup).isNotNull();
		assertThat(partitionGroup.getName()).isEqualTo(tableName);
	}

	@Test
	@DirtiesContext
	public void startSecondTime() { // the partition group is already exist
		service.createPartitionGroup(tableName, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
		PartitionGroup partitionGroupOrig = service.readPartitionGroup(tableName);

		applicationContext.getBean(tableName, PartitionTemplate.class);
		PartitionGroup partitionGroup = service.readPartitionGroup(tableName);
		assertThat(partitionGroup).isEqualTo(partitionGroupOrig);
	}


	@Test
	@DirtiesContext
	public void iteratorTest() {
		service.createPartitionGroup(tableName, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
		PartitionGroup partitionGroupOrig = service.readPartitionGroup(tableName);

		applicationContext.getBean(tableName, PartitionTemplate.class);
		PartitionGroup partitionGroup = service.readPartitionGroup(tableName);
		assertThat(partitionGroup).isEqualTo(partitionGroupOrig);
	}

	@Test
	@DirtiesContext
	public void updatePartitionGroup() {
		service.createPartitionGroup(tableName, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

		final String newGroupName = "newName";
		final int newGroupSize = 10;
		final int newTimeThreshold = 20;
		final int newSizeThreshold = 30;

		service.updatePartitionGroup(newGroupName, newGroupSize, newTimeThreshold, newSizeThreshold);

		applicationContext.getBean(tableName, PartitionTemplate.class);
		PartitionGroup partitionGroup = service.readPartitionGroup(tableName);
		assertThat(partitionGroup.getName().equals(newGroupName));
		assertThat(partitionGroup.getGroupSize() == newGroupSize);
		assertThat(partitionGroup.getSizeThreshold() == newTimeThreshold);
		assertThat(partitionGroup.getSizeThreshold() == newSizeThreshold);
	}

	@Configuration
	@EnableJpaRepositories("com.hp.oo.partitions.repositories")
	@EnableTransactionManagement
	static class Configurator {

		@Bean
		DataSource dataSource() {
			BasicDataSource ds = new BasicDataSource();
			ds.setDriverClassName("org.h2.Driver");
			ds.setUrl("jdbc:h2:mem:test");
			ds.setUsername("sa");
			ds.setPassword("sa");
			ds.setDefaultAutoCommit(false);
			return new TransactionAwareDataSourceProxy(ds);
		}

		@Bean
		SpringLiquibase liquibase(DataSource dataSource) {
			SpringLiquibase liquibase = new SpringLiquibase();
			liquibase.setDataSource(dataSource);
			liquibase.setChangeLog("classpath:/META-INF/database/test.changes.xml");
			SimpleHiloIdentifierGenerator.setDataSource(dataSource);
			return liquibase;
		}

		@Bean
		JpaVendorAdapter jpaVendorAdapter() {
			HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
			adapter.setShowSql(SHOW_SQL);
			adapter.setGenerateDdl(true);
			return adapter;
		}

		@Bean
		@DependsOn("liquibase")
		FactoryBean<EntityManagerFactory> entityManagerFactory(JpaVendorAdapter jpaVendorAdapter) {
			LocalContainerEntityManagerFactoryBean fb = new LocalContainerEntityManagerFactoryBean();
			fb.setDataSource(dataSource());
			fb.setPersistenceProviderClass(HibernatePersistenceProvider.class);
			fb.setPackagesToScan("com.hp.oo.partitions");
			fb.setJpaVendorAdapter(jpaVendorAdapter);
			return fb;
		}

		@Bean
		PartitionService service() {
			return new PartitionServiceImpl();
		}

		@Bean(name = tableName)
		@Lazy
		PartitionTemplate template() {
			return new PartitionTemplateImpl();
		}

		@Bean
		PlatformTransactionManager transactionManager(EntityManagerFactory emf) throws Exception {
			return new JpaTransactionManager(emf);
		}

		@Bean
		TransactionTemplate createTransactionTemplate(PlatformTransactionManager transactionManager) throws Exception {
			return new TransactionTemplate(transactionManager);
		}

		@Bean
		JdbcTemplate jdbcTemplate(DataSource dataSource) {
			return new JdbcTemplate(dataSource);
		}

		@Bean
		public PartitionUtils partitionUtils() {
			return new PartitionUtils();
		}
	}
}