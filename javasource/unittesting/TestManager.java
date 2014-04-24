package unittesting;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runners.JUnit4;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;

import unittesting.proxies.TestSuite;
import unittesting.proxies.UnitTest;
import unittesting.proxies.UnitTestResult;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IFeedback.MessageType;
import com.mendix.webui.CustomJavaAction;
import communitycommons.XPath;

/**
 * @author mwe
 *
 */
public class TestManager extends RunListener
{
	/** Test manager introduces its own exception, because the AssertionExceptions from JUnit are not picked up properly by
	 * the runtime in 4.1.1 and escape all exception handling defined inside microflows :-S
	 * @author mwe
	 *
	 */
	public static class AssertionException extends Exception
	{
		private static final long	serialVersionUID	= -3115796226784699883L;

		public AssertionException(String message)
		{
			super(message);
		}
	}


	private static final String	TEST_MICROFLOW_PREFIX_1	= "Test";
	private static final String	TEST_MICROFLOW_PREFIX_2	= "UT_";
	private static final String	CLOUD_SECURITY_ERROR	= "Failed to find JUnit test classes or methods. Note that java unit tests cannot be run with default cloud security. \n\n";
	private static TestManager	instance;
	public static ILogNode LOG = Core.getLogger("UnitTestRunner");
	private TestSuite	currentTestRun;
	private Map<String, Class<?>[]>	classCache = new HashMap<String, Class<?>[]>();
	private static final Map<String, Object> emptyArguments = new HashMap<String, Object>();
	private IContext context = null;
	private String	lastStep;

	public static TestManager instance()
	{
		if (instance == null)
			instance = new TestManager();
		return instance;
	}


	private Class<?>[] getUnitTestClasses(TestSuite testRun) {
		if (false || !classCache.containsKey(testRun.getModule().toLowerCase())) {

			ArrayList<Class<?>> classlist = getClassesForPackage(testRun.getModule());
			Class<?>[] clazzez =  classlist.toArray(new Class<?>[classlist.size()]);
			classCache.put(testRun.getModule().toLowerCase(), clazzez);
		}

		return classCache.get(testRun.getModule().toLowerCase());
	}


	public synchronized void runTest(UnitTest unitTest, IContext iContext, CustomJavaAction<?> action) throws ClassNotFoundException, CoreException
	{
		this.currentTestRun = unitTest.getUnitTest_TestSuite();
		this.context = iContext;
		
		/**
		 * Is Mf
		 */
		if (unitTest.getIsMf()) {
			try {
				runMfSetup(action);
				runMicroflowTest(unitTest.getName(), unitTest);
			}
			finally {
				runMfTearDown(action);
			}
		}

		/**
		 * Is java
		 */
		else {

			Class<?> test = Class.forName(unitTest.getName().split("/")[0]);

			JUnitCore junit = new JUnitCore();
			junit.addListener(this);

			junit.run(test);
		}
	}

	private void runMfSetup(CustomJavaAction<?> action)  
	{
		if (Core.getMicroflowNames().contains(currentTestRun.getModule() + ".Setup")) {
			try {
				LOG.info("Running Setup microflow..");
				Core.execute(Core.createSystemContext(), currentTestRun.getModule() + ".Setup", emptyArguments);
			}
			catch(Exception e) {
				LOG.error("Exception during SetUp microflow: " + e.getMessage(), e);
				if (action != null)
					action.addTextMessageFeedback(MessageType.WARNING, "Failed to start unit test. SetUp microflow threw an exception: " + e.getMessage(), true);
				else 
					throw new RuntimeException(e);
			}
		}
	}

	private void runMfTearDown(CustomJavaAction<?> action) 
	{
		if (Core.getMicroflowNames().contains(currentTestRun.getModule() + ".TearDown")) {
			try
			{
				LOG.info("Running TearDown microflow..");
				Core.execute(Core.createSystemContext(), currentTestRun.getModule() + ".TearDown", emptyArguments);
			}
			catch (Exception e)
			{
				LOG.error("Severe: exception in unittest TearDown microflow '" + currentTestRun.getModule() + ".Setup': " +e.getMessage(), e);
				if (action != null)
					action.addTextMessageFeedback(MessageType.WARNING, "Failed to start unit test. TearDown microflow threw an exception: " + e.getMessage(), true);
				else 
					throw new RuntimeException(e);
			}
		}
	}

	public synchronized boolean runAllTests(TestSuite testRun, IContext iContext, CustomJavaAction<?> action) throws CoreException
	{
		LOG.info("Starting testrun on " + testRun.getModule());
		context = iContext;

		long start = System.currentTimeMillis();

		/**
		 * Run java unit tests
		 */
		Class<?>[] clazzez = null;
		try {
			clazzez = getUnitTestClasses(testRun);
		}
		catch(Exception e) {
			LOG.error(CLOUD_SECURITY_ERROR + e.getMessage(), e);
		}

		this.currentTestRun = testRun;

		if (clazzez != null && clazzez.length > 0) {
			JUnitCore junit = new JUnitCore();
			junit.addListener(this);

			junit.run(clazzez);
		}

		/** 
		 * Run microflow tests
		 * 
		 */

		try {
			runMfSetup(action);
	
			List<String> mfnames = findMicroflowUnitTests(testRun);
	
			for (String mf : mfnames){
				currentTestRun.setTestCount(currentTestRun.getTestCount() + 1);
				if (!runMicroflowTest(mf, getUnitTest(mf, true)))
					currentTestRun.setTestFailedCount(currentTestRun.getTestFailedCount() + 1);
			}
	
		}
		finally {
			runMfTearDown(action);
		}
		

		/** 
		 * Aggregate
		 */
		testRun.setLastRunTime((System.currentTimeMillis() - start) / 1000);
		testRun.commit();

		LOG.info("Finished testrun on " + testRun.getModule());
		return true;
	}


	public List<String> findMicroflowUnitTests(TestSuite testRun)
	{
		List<String> mfnames = new ArrayList<String>();

		String basename1 = (testRun.getModule() + "." + TEST_MICROFLOW_PREFIX_1).toLowerCase();
		String basename2 = (testRun.getModule() + "." + TEST_MICROFLOW_PREFIX_2).toLowerCase();
		
		//Find microflownames
		for (String mf : Core.getMicroflowNames()) 
			if (mf.toLowerCase().startsWith(basename1) || mf.toLowerCase().startsWith(basename2)) 
				mfnames.add(mf);

		//Sort microflow names
		Collections.sort(mfnames);
		return mfnames;
	}


	private boolean runMicroflowTest(String mf, UnitTest test)
	{
		/** 
		 * Prepare...
		 */
		LOG.info("Starting unittest " + mf);

		reportStep("Starting microflow test '" + mf + "'");
		
		test.setResult(UnitTestResult._1_Running);
		test.setName(mf);
		test.setResultMessage("");
		test.setLastRun(new Date());

		if (Core.getInputParameters(mf).size() != 0) {
			test.setResultMessage("Unable to start test '" +  mf + "', microflow has parameters");
			test.setResult(UnitTestResult._2_Failed);
		}
		else if (Core.getReturnType(mf).getType() != IDataType.DataTypeEnum.Boolean && 
						 Core.getReturnType(mf).getType() != IDataType.DataTypeEnum.String &&
						 Core.getReturnType(mf).getType() != IDataType.DataTypeEnum.Nothing) {
			
			test.setResultMessage("Unable to start test '" +  mf + "', microflow should return either a boolean or a string or nothing at all");
			
			test.setResult(UnitTestResult._2_Failed);
		}

		commitSilent(test);

		IContext mfContext = Core.createSystemContext();
		if (this.currentTestRun.getAutoRollbackMFs())
			mfContext.startTransaction();

		long start = System.currentTimeMillis();

		try {
			Object resultObject = Core.execute(mfContext, mf, emptyArguments);
			
			start = System.currentTimeMillis() - start;
			boolean res = 	resultObject == null || Boolean.TRUE.equals(resultObject) || "".equals(resultObject);
				
			test.setResult(res ? UnitTestResult._3_Success : UnitTestResult._2_Failed);
			test.setResultMessage((res ? "OK" : "FAILED") +  " in " + (start > 10000 ? Math.round(start / 1000) + " s." : start + " ms. Result: " + String.valueOf(resultObject)));
			return res;
		}
		catch(Exception e) {
			test.setResult(UnitTestResult._2_Failed);
			Throwable cause = ExceptionUtils.getRootCause(e);
			if (cause != null && cause instanceof AssertionException)
				test.setResultMessage(cause.getMessage());
			else
				test.setResultMessage("Exception: " + e.getMessage() + "\n\n" + ExceptionUtils.getFullStackTrace(e));
			return false;
			
		}
		finally {
			if (this.currentTestRun.getAutoRollbackMFs())
				mfContext.rollbackTransAction();
				
			test.setLastStep(lastStep);
			commitSilent(test);

			LOG.info("Finished unittest " + mf + ": " + test.getResult());
		}
	}


	private void commitSilent(UnitTest test)
	{
		try
		{
			test.commit();
		}
		catch (CoreException e)
		{
			throw new RuntimeException(e);
		}
	}

	private UnitTest getUnitTest(Description description, boolean isMF) {
		return getUnitTest(description.getClassName() + "/" + description.getMethodName(), isMF);
	}

	private UnitTest getUnitTest(String name, boolean isMF) {
		UnitTest res;
		try
		{
			res = XPath.create(context, UnitTest.class)
					.eq(UnitTest.MemberNames.UnitTest_TestSuite, currentTestRun)
					.and()
					.eq(UnitTest.MemberNames.Name, name)
					.and()
					.eq(UnitTest.MemberNames.IsMf, isMF)
					.first();
		}
		catch (CoreException e)
		{
			throw new RuntimeException(e);
		}

		if (res == null) {
			res = new UnitTest(context);
			res.setName(name);
			res.setUnitTest_TestSuite(currentTestRun);
			res.setIsMf(isMF);
		}

		return res;
	}


	/*** 
	 * 
	 * 
	 * 
	 * Junit run listener
	 * 
	 */


	@Override
	public void testRunStarted(Description description) throws java.lang.Exception {
		LOG.info("Starting test run");
	}

	@Override
	public void testRunFinished(Result result) throws java.lang.Exception {
		LOG.info("Test run finished");
	}

	@Override
	public void testStarted(Description description) throws java.lang.Exception {
		String message = "Starting JUnit test " + description.getClassName() + "." + description.getMethodName();
		LOG.info(message);
		reportStep(message);
		
		UnitTest t = getUnitTest(description, false);
		t.setResult(UnitTestResult._1_Running);
		t.setResultMessage("");
		t.setLastRun(new Date());
		t.commit();
	}

	@Override
	public void testFinished(Description description) throws Exception {
		LOG.info("Finished test " + description.getClassName() + "." + description.getMethodName());

		UnitTest t = getUnitTest(description, false);

		currentTestRun.setTestCount(currentTestRun.getTestCount() + 1);

		if (t.getResult() == UnitTestResult._1_Running) {
			t.setResult(UnitTestResult._3_Success);

			long delta = getUnitTestInnerTime(description, t);

			t.setResultMessage("OK in " + (delta > 10000 ? Math.round(delta / 1000) + " s" : delta + " ms"));

		}
		
		t.setLastStep(lastStep);
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


	@Override
	public void testFailure(Failure failure) throws java.lang.Exception {
		boolean isCloudSecurityError = 
				failure.getException() != null && 
				failure.getException() instanceof AccessControlException &&
				((AccessControlException) failure.getException()).getPermission().getName().equals("accessDeclaredMembers");
				
			
		
		UnitTest t = getUnitTest(failure.getDescription(), false);

		/** 
		 * Test itself failed
		 */		
		LOG.error("Failed test (at step '" + lastStep + "') " + failure.getDescription().getClassName() + "." + failure.getDescription().getMethodName() + " : " + failure.getMessage(), failure.getException());

		currentTestRun.setTestFailedCount(currentTestRun.getTestFailedCount() + 1);

		t.setResult(UnitTestResult._2_Failed);
		t.setResultMessage(String.format("%s %s: %s\n\n:%s",
				isCloudSecurityError ? "CLOUD SECURITY EXCEPTION \n\n" + CLOUD_SECURITY_ERROR : "FAILED",
				findProperExceptionLine(failure.getTrace()),
				failure.getMessage(),
				failure.getTrace()
				));
		
		t.setLastStep(lastStep);
		t.setLastRun(new Date());
		t.commit();
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

	

	/**
	 * 
	 * 
	 * Find runabble classes
	 * 
	 * https://github.com/ddopson/java-class-enumerator/blob/master/src/pro/ddopson/ClassEnumerator.java
	 * 
	 */

	private Class<?> loadClass(String className) {
		try {
			return this.getClass().getClassLoader().loadClass(className);
		} 
		catch (ClassNotFoundException e) {
			throw new RuntimeException("Unexpected ClassNotFoundException loading class '" + className + "'");
		}
	}

	private void processDirectory(File directory, String pkgname, ArrayList<Class<?>> classes) {
		// Get the list of the files contained in the package
		String[] files = directory.list();
		if (files != null) 
			for (int i = 0; i < files.length; i++) {
				String fileName = files[i];
				String className = null;
				// we are only interested in .class files
				if (fileName.endsWith(".class")) {
					// removes the .class extension
					className = pkgname + '.' + fileName.substring(0, fileName.length() - 6);
				}
				if (className != null) {
					Class<?> clazz = loadClass(className);
					if (isProperUnitTest(clazz))
						classes.add(clazz);
				}
				File subdir = new File(directory, fileName);
				if (subdir.isDirectory()) {
					processDirectory(subdir, pkgname + '.' + fileName, classes);
				}
			}
	}

	private boolean isProperUnitTest(Class<?> clazz)
	{
		for (Method m : clazz.getMethods()) 
			if (m.getAnnotation(org.junit.Test.class) != null)
				return true;

		return false;

	}


	public ArrayList<Class<?>> getClassesForPackage(String path /*Package pkg*/) {
		ArrayList<Class<?>> classes = new ArrayList<Class<?>>();

		//String pkgname = pkg.getName();
		//String relPath = pkgname.replace('.', '/');

		//Lowercased Mendix Module names equals their package names
		String pkgname = path.toLowerCase();

		// Get a File object for the package
		File basedir = new File(Core.getConfiguration().getBasePath() + File.separator + "run" +  File.separator + "bin" + File.separator + pkgname);
		processDirectory(basedir, pkgname, classes);

		return classes;
	}


	public void reportStep(String lastStep1)
	{
		if (currentTestRun != null) {
			lastStep = lastStep1;
			LOG.debug("UnitTest reportStep: '" + lastStep1 + "'");
		}
		else
			LOG.warn("Cannot update laststep to '" + lastStep1 + "': No test is currently running..");
	}


	public synchronized void updateUnitTestList(TestSuite testSuite, IContext context1)
	{
		this.context = context1; 
		
		try {
			/*
			 * Mark all dirty
			 */
			for(UnitTest test : XPath.create(context, UnitTest.class)
					.eq(UnitTest.MemberNames.UnitTest_TestSuite, testSuite)
					.all()) {
				test.set_dirty(true);
				test.commit();
			}
			
			/*
			 * Find microflow tests
			 */
			for (String mf : findMicroflowUnitTests(testSuite)) { 
				UnitTest test = getUnitTest(mf, true);
				test.set_dirty(false);
				test.setUnitTest_TestSuite(testSuite);
				test.commit();
			}
			
			/*
			 * Find Junit tests
			 */
			for (String jtest : findJUnitTests(testSuite)) { 
				UnitTest test = getUnitTest(jtest, false);
				test.set_dirty(false);
				test.setUnitTest_TestSuite(testSuite);
				test.commit();
			}
			
			/*
			 * Delete dirty tests
			 */
			for(UnitTest test : XPath.create(context, UnitTest.class)
					.eq(UnitTest.MemberNames._dirty, true)
					.all()) {
				test.delete();
			}
			
		}
		catch(Exception e) {
			LOG.error("Failed to update unit test list: " + e.getMessage(), e);
		}
		
	}


	public List<String> findJUnitTests(TestSuite testSuite)
	{
		List<String> junitTests = new ArrayList<String>(); 
		try {
			Class<?>[] clazzez = getUnitTestClasses(testSuite);

			if (clazzez != null && clazzez.length > 0) { 
				for (Class<?> clazz : clazzez) {
					
					//From https://github.com/KentBeck/junit/blob/master/src/main/java/org/junit/runners/BlockJUnit4ClassRunner.java method computeTestMethods
					try {
						List<FrameworkMethod> methods =	new JUnit4(clazz).getTestClass().getAnnotatedMethods(Test.class);
						
						if (methods != null && !methods.isEmpty()) 
							for (FrameworkMethod method: methods)
								junitTests.add(clazz.getName() + "/" + method.getName());
					}
					catch(InitializationError e2) {
						StringBuilder errors = new StringBuilder();
						
						for(Throwable cause : e2.getCauses())
							errors.append("\n").append(cause.getMessage());
						
						LOG.error("Failed to recognize class '" + clazz + "' as unitTestClass: " + errors.toString());
					}
				}
			}
		}
		catch(Exception e) {
			LOG.error(CLOUD_SECURITY_ERROR + e.getMessage(), e);
		}
		return junitTests;
	}
}