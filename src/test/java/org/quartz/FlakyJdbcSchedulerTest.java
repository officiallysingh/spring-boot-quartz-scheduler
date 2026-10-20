package org.quartz;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.quartz.impl.DirectSchedulerFactory;
import org.quartz.impl.SchedulerRepository;
import org.quartz.impl.jdbcjobstore.JdbcQuartzTestUtilities;
import org.quartz.impl.jdbcjobstore.JobStoreTX;
import org.quartz.impl.jdbcjobstore.JdbcQuartzTestUtilities.DatabaseType;
import org.quartz.simpl.SimpleThreadPool;
import org.quartz.utils.ConnectionProvider;
import org.quartz.utils.DBConnectionManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

public class FlakyJdbcSchedulerTest extends AbstractSchedulerTest {

    private Random random = new Random();
    private float createFailureProb = 0.0f;
    private float preCommitFailureProb = 0.0f;
    private float postCommitFailureProb = 0.0f;

    @MethodSource("data")
    public static Stream<Arguments> data() {
        return Stream.of(
                Arguments.of(0f, 0f, 0f),
                Arguments.of(0.2f, 0f, 0f),
                Arguments.of(0f, 0.2f, 0f),
                Arguments.of(0f, 0f, 0.2f),
                Arguments.of(0.2f, 0.2f, 0.2f)
        );
    }

    @Override
    protected Scheduler createScheduler(String name, int threadPoolSize) throws SchedulerException {
        try {
            DBConnectionManager.getInstance().addConnectionProvider(name, new FlakyConnectionProvider(name));
        } catch (SQLException ex) {
            throw new SchedulerException("Failed to create scheduler", ex);
        }

        JobStoreTX jobStore = new JobStoreTX();
        jobStore.setDataSource(name);
        jobStore.setTablePrefix("QRTZ_");
        jobStore.setInstanceId("AUTO");
        jobStore.setDbRetryInterval(50);

        DirectSchedulerFactory.getInstance().createScheduler(
                name + "Scheduler",
                "AUTO",
                new SimpleThreadPool(threadPoolSize, Thread.NORM_PRIORITY),
                jobStore,
                null,
                0,
                -1,
                50
        );

        return SchedulerRepository.getInstance().lookup(name + "Scheduler");
    }

    @ParameterizedTest
    @MethodSource("data")
    void testTriggerFiring(float createFailureProb, float preCommitFailureProb, float postCommitFailureProb) throws Exception {
        this.createFailureProb = createFailureProb;
        this.preCommitFailureProb = preCommitFailureProb;
        this.postCommitFailureProb = postCommitFailureProb;
        this.random = new Random();

        final int jobCount = 100;
        final int execCount = 5;
        Scheduler scheduler = createScheduler("testTriggerFiring", 2);

        try {
            for (int i = 0; i < jobCount; i++) {
                String jobName = "myJob" + i;
                JobDetail jobDetail = JobBuilder.newJob(TestJob.class)
                        .withIdentity(jobName, "myJobGroup")
                        .usingJobData("data", 0)
                        .storeDurably()
                        .requestRecovery()
                        .build();

                Trigger trigger = TriggerBuilder.newTrigger()
                        .withIdentity("triggerName" + i, "triggerGroup")
                        .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(1).withRepeatCount(execCount - 1))
                        .build();

                if (!scheduler.checkExists(jobDetail.getKey())) {
                    scheduler.scheduleJob(jobDetail, trigger);
                }
            }

            scheduler.start();

            for (int i = 0; i < TimeUnit.MINUTES.toSeconds(5); i++) {
                int doneCount = 0;
                for (int j = 0; j < jobCount; j++) {
                    JobDetail jobDetail = scheduler.getJobDetail(new JobKey("myJob" + j, "myJobGroup"));
                    if (jobDetail != null && jobDetail.getJobDataMap().getInt("data") >= execCount) {
                        doneCount++;
                    }
                }
                if (doneCount == jobCount) {
                    return;
                }
                TimeUnit.SECONDS.sleep(1);
            }
            fail("Not all jobs completed as expected.");
        } finally {
            scheduler.shutdown(true);
        }
    }

    @PersistJobDataAfterExecution
    @DisallowConcurrentExecution
    public static class TestJob implements Job {
        public void execute(JobExecutionContext context) {
            JobDataMap dataMap = context.getJobDetail().getJobDataMap();
            int val = dataMap.getInt("data") + 1;
            dataMap.put("data", val);
        }
    }

    private void createFailure() throws SQLException {
        if (random.nextFloat() < createFailureProb) {
            throw new SQLException("FlakyConnection failed on you on creation.");
        }
    }

    private void preCommitFailure() throws SQLException {
        if (random.nextFloat() < preCommitFailureProb) {
            throw new SQLException("FlakyConnection failed on you pre-commit.");
        }
    }

    private void postCommitFailure() throws SQLException {
        if (random.nextFloat() < postCommitFailureProb) {
            throw new SQLException("FlakyConnection failed on you post-commit.");
        }
    }

    private class FlakyConnectionProvider implements ConnectionProvider {
        private final Thread safeThread;
        private final String delegateName;

        private FlakyConnectionProvider(String name) throws SQLException {
            this.delegateName = "delegate_" + name;
            this.safeThread = Thread.currentThread();
            JdbcQuartzTestUtilities.createDatabase(delegateName, DatabaseType.DERBY);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (Thread.currentThread() == safeThread) {
                return DBConnectionManager.getInstance().getConnection(delegateName);
            } else {
                createFailure();
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class[]{Connection.class},
                        new FlakyConnectionInvocationHandler(DBConnectionManager.getInstance().getConnection(delegateName))
                );
            }
        }

        @Override
        public void shutdown() throws SQLException {
            DBConnectionManager.getInstance().shutdown(delegateName);
            JdbcQuartzTestUtilities.destroyDatabase(delegateName, DatabaseType.DERBY);
            JdbcQuartzTestUtilities.shutdownDatabase(delegateName, DatabaseType.DERBY);
        }

        @Override
        public void initialize() throws SQLException {
            // No-op
        }
    }

    private class FlakyConnectionInvocationHandler implements InvocationHandler {
        private final Connection delegate;

        public FlakyConnectionInvocationHandler(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("commit".equals(method.getName())) {
                preCommitFailure();
                method.invoke(delegate, args);
                postCommitFailure();
                return null;
            } else {
                return method.invoke(delegate, args);
            }
        }
    }
}