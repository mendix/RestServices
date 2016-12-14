package unittesting;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.http.auth.InvalidCredentialsException;
import org.json.JSONArray;
import org.json.JSONObject;

import unittesting.proxies.TestSuite;
import unittesting.proxies.UnitTest;
import unittesting.proxies.UnitTestResult;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.externalinterface.connector.RequestHandler;
import com.mendix.logging.ILogNode;
import com.mendix.m2ee.api.IMxRuntimeRequest;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import communitycommons.XPath;

public class RemoteApiServlet extends RequestHandler {

	private static final Object COMMAND_START = "start";
	private static final Object COMMAND_STATUS = "status";
	private static final String PARAM_PASSWORD = "password";
	
	private final String password;
	private boolean detectedUnitTests = false;
	
	private final static ILogNode LOG = TestManager.LOG;
	private volatile TestSuiteRunner testSuiteRunner;
	
	public RemoteApiServlet(String password) {
		this.password = password;
	}

	@Override
	protected void processRequest(IMxRuntimeRequest req,
			IMxRuntimeResponse resp, String path) throws Exception {
		
		HttpServletRequest request = req.getHttpServletRequest();
		HttpServletResponse response = resp.getHttpServletResponse();
		
		try {
			if (!"POST".equals(request.getMethod()))
				response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			else if (COMMAND_START.equals(path))
				serveRunStart(request, response, path);
			else if (COMMAND_STATUS.equals(path))
				serveRunStatus(request, response, path);
			else
				response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		}
		catch (IllegalArgumentException e) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			write(response, e.getMessage());
		}
		catch (InvalidCredentialsException e) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			write(response, "Invalid password provided");
		}
	}

	private void write(HttpServletResponse response, String data) {
		try {
			response.getOutputStream().write(data.getBytes("UTF-8"));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
	}

	private synchronized void serveRunStatus(HttpServletRequest request,
			HttpServletResponse response, String path) throws Exception {
		JSONObject input = parseInput(request);
		verifyPassword(input);
		
		if (testSuiteRunner == null) {
			throw new IllegalArgumentException("No testrun was started yet");
		}
		
		response.setStatus(HttpServletResponse.SC_OK);
		response.setHeader("Content-Type", "application/json");
		write(response, testSuiteRunner.getStatus().toString(4));
	}

	private synchronized void serveRunStart(HttpServletRequest request,
			HttpServletResponse response, String path) throws IOException, CoreException, InvalidCredentialsException {
		JSONObject input = parseInput(request);
		verifyPassword(input);
	
		IContext context = Core.createSystemContext();
		if (!detectedUnitTests) {
			TestManager.instance().findAllTests(context);
			detectedUnitTests = true;
		}
		
		if (testSuiteRunner != null && !testSuiteRunner.isFinished()) {
			throw new IllegalArgumentException("Cannot start a test run while another test run is still running");
		}
		
		LOG.info("[remote api] starting new test run");
		testSuiteRunner = new TestSuiteRunner();
		
		Thread t = new Thread() {
			@Override
			public void run() {
				testSuiteRunner.run();
			}
		};
		
		t.start();
		response.setStatus(HttpServletResponse.SC_NO_CONTENT);		
	}
		
	

	private void verifyPassword(JSONObject input) throws InvalidCredentialsException {
		if (!input.has(PARAM_PASSWORD)) {
			LOG.warn("[remote api] Missing password");
			throw new IllegalArgumentException("No '" + PARAM_PASSWORD + "' attribute found in the JSON body. Please provide a password");
		}
		
		if (!password.equals(input.getString(PARAM_PASSWORD))) {
			LOG.warn("[remote api] Invalid password");
			throw new InvalidCredentialsException();
		}
	}

	private JSONObject parseInput(HttpServletRequest request) throws IOException {
		String data = IOUtils.toString(request.getInputStream());
		return new JSONObject(data);
	}

	private class TestSuiteRunner {
		boolean finished = false;
		long startTime = System.currentTimeMillis();
		long totalTime = -1;
		
		public void run() {
			try {
				TestManager.instance().runTestSuites();
			} catch (CoreException e) {
				LOG.error("[remote api] error while running test suite: " + e.getMessage(), e);
			} finally {
				totalTime = System.currentTimeMillis() - startTime;
				finished = true;
				LOG.info("[remote api] finished test run");
			}			
		}
		
		public synchronized boolean isFinished() {
			return finished;
		}

		public synchronized JSONObject getStatus() throws CoreException {
			JSONObject result = new JSONObject();
			result.put("completed", this.finished);
			result.put("runtime", totalTime);
			
			IContext context = Core.createSystemContext();
			long count = 0l;
			long failures = 0l;
			
			for(TestSuite suite : XPath.create(context, TestSuite.class).all()) {
				count += suite.getTestCount();
				failures += suite.getTestFailedCount();
			}
			
			result.put("tests", count);
			result.put("failures", failures);
			
			JSONArray failedTests = new JSONArray();
			result.put("failed_tests", failedTests);
			
			for(UnitTest test : XPath.create(context, UnitTest.class)
					//failed tests
					.eq(UnitTest.MemberNames.Result, UnitTestResult._2_Failed)
					//in testsuites which are not running anymore
					.eq(UnitTest.MemberNames.UnitTest_TestSuite, TestSuite.entityName, TestSuite.MemberNames.Result, UnitTestResult._2_Failed)
					.all()) 
			{
				JSONObject i = new JSONObject();
				i.put("name", test.getName());
				i.put("error", test.getResultMessage());
				i.put("step", test.getLastStep());
				failedTests.put(i);
			}
			
			return result;
		}	
	}

}
