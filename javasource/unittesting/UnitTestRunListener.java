package unittesting;

import java.lang.reflect.InvocationTargetException;
import java.security.AccessControlException;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import com.mendix.systemwideinterfaces.core.IContext;

import unittesting.proxies.TestSuite;
import unittesting.proxies.UnitTest;
import unittesting.proxies.UnitTestResult;

public class UnitTestRunListener extends RunListener {
	

	private IContext context;
	private TestSuite testSuite;

	public UnitTestRunListener(IContext context, TestSuite testSuite) {
		this.context = context;
		this.testSuite = testSuite;
	}

	@Override
	public void testRunStarted(Description description) throws java.lang.Exception {
		TestManager.LOG.info("Starting test run");
	}

	@Override
	public void testRunFinished(Result result) throws java.lang.Exception {
		TestManager.LOG.info("Test run finished");
	}

	@Override
	public void testStarted(Description description) throws java.lang.Exception {
		String message = "Starting JUnit test " + description.getClassName() + "." + description.getMethodName();
		TestManager.LOG.info(message);
		TestManager.instance().reportStep(message);
		
		UnitTest t = getUnitTest(description);
		t.setResult(UnitTestResult._1_Running);
		t.setResultMessage("");
		t.setLastRun(new Date());
		t.commit();
	}

	private UnitTest getUnitTest(Description description) {
		return TestManager.instance().getUnitTest(context, testSuite, description, false);
	}

	@Override
	public void testFinished(Description description) throws Exception {
		TestManager.LOG.info("Finished test " + description.getClassName() + "." + description.getMethodName());
		
		UnitTest t = getUnitTest(description);

		if (t.getResult() == UnitTestResult._1_Running) {
			t.setResult(UnitTestResult._3_Success);

			long delta = getUnitTestInnerTime(description, t);

			t.setResultMessage("JUnit test completed successfully");
			t.setReadableTime((delta > 10000 ? Math.round(delta / 1000) + " seconds" : delta + " milliseconds"));
		}
		
		t.setLastStep(TestManager.instance().getLastReportedStep());
		t.commit();
	}

	@Override
	public void testFailure(Failure failure) throws java.lang.Exception {
		boolean isCloudSecurityError = 
				failure.getException() != null && 
				failure.getException() instanceof AccessControlException &&
				((AccessControlException) failure.getException()).getPermission().getName().equals("accessDeclaredMembers");
		
		UnitTest t = getUnitTest(failure.getDescription());

		/** 
		 * Test itself failed
		 */		
		TestManager.LOG.error("Failed test (at step '" + TestManager.instance().getLastReportedStep() + "') " + failure.getDescription().getClassName() + "." + failure.getDescription().getMethodName() + " : " + failure.getMessage(), failure.getException());

		testSuite.setTestFailedCount(testSuite.getTestFailedCount() + 1);
		testSuite.commit();
		
		t.setResult(UnitTestResult._2_Failed);
		t.setResultMessage(String.format("%s %s: %s\n\n:%s",
				isCloudSecurityError ? "CLOUD SECURITY EXCEPTION \n\n" + TestManager.CLOUD_SECURITY_ERROR : "FAILED",
				findProperExceptionLine(failure.getTrace()),
				failure.getMessage(),
				failure.getTrace()
				));
		
		t.setLastStep(TestManager.instance().getLastReportedStep());
		t.setLastRun(new Date());
		t.commit();
	}

	private long getUnitTestInnerTime(Description description, UnitTest t)
			throws IllegalAccessException, IllegalArgumentException,
			InvocationTargetException, NoSuchMethodException, SecurityException
			{
		long delta = System.currentTimeMillis() - t.getLastRun().getTime();

		if (AbstractUnitTest.class.isAssignableFrom(description.getTestClass())) 
			delta = (Long) description.getTestClass().getMethod("getTestRunTime").invoke(null);
		return delta;
	}

	private String findProperExceptionLine(String trace)
	{
		String[] lines = StringUtils.split(trace,"\n");
		if (lines.length > 2)
			for(int i = 1; i < lines.length; i++) {
				String line = lines[i].trim();
				if (!line.startsWith("at org.junit") && line.contains("(")) 
					return " at " + line.substring(line.indexOf('(') + 1, line.indexOf(')')).replace(":"," line ");
			}
		
		return "";
	}
}
