package tests;

import java.util.concurrent.ExecutionException;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import restservices.RestServices;
import restservices.consume.RestConsumeException;
import restservices.consume.RestConsumer;
import restservices.proxies.RequestResult;
import restservices.proxies.ResponseCode;
import restservices.proxies.ServiceDefinition;
import restservices.publish.ChangeManager;
import system.proxies.User;
import system.proxies.UserRole;
import tests.proxies.CTaskView;
import tests.proxies.Task;

import com.google.common.collect.ImmutableList;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IContext;

import communitycommons.StringUtils;
import communitycommons.XPath;

public class BasicTests {

	private ServiceDefinition def;
	private String baseUrl;
	private RequestResult lastRequestResult;
	private String username;

	@Before
	public void setup() throws CoreException {
		XPath.create(Core.createSystemContext(), Task.class).deleteAll();
		
		this.def = XPath.create(Core.createSystemContext(), ServiceDefinition.class).findOrCreateNoCommit(ServiceDefinition.MemberNames.Name, "tasks");
		def.setEnableGet(true);
		def.setEnableListing(true);
		def.setAccessRole("*");
		def.setSourceEntity(Task.entityName);
		def.setSourceKeyAttribute(Task.MemberNames.Nr.toString());
		def.setOnPublishMicroflow("Tests.TaskToView");
		def.setEnableChangeTracking(false);
		def.commit();
		
		this.baseUrl = RestServices.getServiceUrl("tasks");
	}
	
	@After
	public void tearDown() throws CoreException {
		if (username != null) {
			XPath.create(Core.createSystemContext(), User.class).eq(User.MemberNames.Name, username).deleteAll();
			username = null;
		}
	}
	
	private String getTestUser() throws CoreException {
		if (username == null){
			IContext c = Core.createSystemContext();
			User user = XPath.create(c, User.class).findOrCreate(
					User.MemberNames.Name, StringUtils.randomHash(),
					User.MemberNames.Password, "Password1!");

			user.setUserRoles(XPath.create(c, UserRole.class).eq(UserRole.MemberNames.Name, "User").all());
			user.commit();
			username = user.getName();
		}
		return username;
	}
	
	private Task createTask(IContext c, String description, boolean done) throws CoreException {
		Task t = new Task(c);
		t.setDescription(description);
		t.setCompleted(done);
		t.commit();
		return t;
	}
	
	private CTaskView getTask(IContext c, String nr, String eTag, ResponseCode respCode, long status) throws Exception {
		CTaskView task = new CTaskView(c);
		RequestResult res = RestConsumer.getObject(c, baseUrl + nr, eTag, task.getMendixObject());
		this.lastRequestResult = res;
		
		Assert.assertEquals(respCode, res.getResponseCode());
		Assert.assertEquals(status, (long) res.getRawResponseCode());
		return task;
	}
	
	@Test
	public void simpleGet() throws Exception {
		IContext c = Core.createSystemContext();
		Task t = createTask(c, "Fetch milk", false);
		publishTask(c, t, false);
		
		IContext c2 = Core.createSystemContext();
		CTaskView v;
		
		//get
		v = getTask(c2, t.getNr().toString(), null, ResponseCode.OK, 200);
		Assert.assertEquals("Fetch milk", v.getDescription());
		Assert.assertEquals(t.getNr(), v.getNr());
		Assert.assertEquals(false, v.getCompleted());
		Assert.assertTrue(lastRequestResult.getResponseBody().contains("milk"));

		//get another time
		v = getTask(c2, t.getNr().toString(), "", ResponseCode.OK, 200);
		Assert.assertEquals("Fetch milk", v.getDescription());
		Assert.assertEquals(t.getNr(), v.getNr());
		Assert.assertEquals(false, v.getCompleted());
		Assert.assertTrue(lastRequestResult.getResponseBody().contains("milk"));

		//use etag, should return modified and nothing sensible
		v = getTask(c2, t.getNr().toString(), v.getETag(), ResponseCode.NotModified, 304);
		Assert.assertEquals(null, v.getDescription());
		Assert.assertEquals(0L, (long) v.getNr());
		Assert.assertEquals(false, v.getCompleted());

		//use invalid eTag, should return the object
		v = getTask(c2, t.getNr().toString(), "blablabla", ResponseCode.OK, 200);
		Assert.assertEquals("Fetch milk", v.getDescription());
		Assert.assertEquals(t.getNr(), v.getNr());
		Assert.assertEquals(false, v.getCompleted());

		//use invalid nr, should return 404
		try {
			v = getTask(c2, "-17", v.getETag(), ResponseCode.NotModified, 304);
			Assert.fail();
		}
		catch(RestConsumeException e) {
			Assert.assertEquals(404L, e.getStatus());
		}
		
		//use invalid key, should return 404
		try {
			v = getTask(c2, "iamnotanumber", null, ResponseCode.OK, 200);
			Assert.fail();
		}
		catch(RestConsumeException e) { 
			Assert.assertEquals(404L, e.getStatus());
		}
		
		//use other contentType, should work but not set data
		RequestResult xmlresult = RestConsumer.getObject(c, baseUrl + t.getNr().toString() + "?contenttype=xml", null, null);
		
		Assert.assertEquals(200L, (long)(int)xmlresult.getRawResponseCode());
		Assert.assertTrue(xmlresult.getResponseBody().startsWith("<?xml"));
		Assert.assertTrue(xmlresult.getResponseBody().contains("milk"));
		
		//disble the service
		def.setEnableGet(false);
		def.setEnableListing(false);
		def.commit();
		
		try {
			v = getTask(c2, v.getNr().toString(), null, ResponseCode.OK, 200);
			Assert.fail();
		}
		catch(RestConsumeException e) {
			Assert.assertEquals(405L, e.getStatus()); //Method  not allowed
		}
		
		//enable, set constraint, find
		def.setEnableGet(true);
		def.setEnableListing(true);
		def.setSourceConstraint("[" + Task.MemberNames.Completed.toString() + " = false()]");
		def.commit();
		
		v = getTask(c2, t.getNr().toString(), null, ResponseCode.OK, 200);
		Assert.assertEquals("Fetch milk", v.getDescription());
		Assert.assertEquals(t.getNr(), v.getNr());
		Assert.assertEquals(false, v.getCompleted());
		
		//make task no longer match constraint
		t.setCompleted(true);
		publishTask(c,t,false);
		
		try {
			v = getTask(c2, t.getNr().toString(), null, ResponseCode.OK, 200);
			Assert.fail();
		}
		catch(RestConsumeException e) { 
			Assert.assertEquals(404L, e.getStatus());
		}
		
		t.setCompleted(false);
		publishTask(c2, t, false);
		
		//enable but secure the service
		def.setAccessRole("User");
		def.setSourceConstraint("");
		def.commit();
		
		try {
			v = getTask(c2, v.getNr().toString(), null, ResponseCode.OK, 200);
			Assert.fail();
		}
		catch(RestConsumeException e) { 
			Assert.assertEquals(401L, e.getStatus());
		}
		
		//secure, use valid user
		RestConsumer.addCredentialsToNextRequest(getTestUser(), "Password1!");

		v = getTask(c2, t.getNr().toString(), null, ResponseCode.OK, 200);
		Assert.assertEquals("Fetch milk", v.getDescription());
		Assert.assertEquals(t.getNr(), v.getNr());
		Assert.assertEquals(false, v.getCompleted());
		Assert.assertTrue(lastRequestResult.getResponseBody().contains("milk"));
		
		//secure, but does not match constraint
		def.setSourceConstraint("[" + Task.MemberNames.Completed.toString() + " = false()]");
		def.commit();
		RestConsumer.addCredentialsToNextRequest(getTestUser(), "Password1!");

		v = getTask(c2, t.getNr().toString(), null, ResponseCode.OK, 200);
		try {
			v = getTask(c2, t.getNr().toString(), null, ResponseCode.OK, 200);
			Assert.fail();
		}
		catch(RestConsumeException e) { 
			Assert.assertEquals(401L, e.getStatus());
		}
		
		//unsecure, and delete
		def.setAccessRole("*");
		def.commit();
		
		t.delete();
		publishTask(c, t, true);
	}
	
	
	
	private void publishTask(IContext c, Task t, boolean delete) throws CoreException {
		if (delete) {
			ChangeManager.publishDelete(c, t.getMendixObject());
			t.delete();
		}
		else {
			ChangeManager.publishUpdate(c, t.getMendixObject());
			t.commit();
		}
	}

	@Test
	public void getFromIndex() throws Exception {
		def.setEnableChangeTracking(true);
		def.commit();
		
		rebuildIndex();
		
		simpleGet();

	}

	private void rebuildIndex() throws CoreException, InterruptedException, ExecutionException {
		RestServices.getService("tasks").getChangeManager().rebuildIndex();
	}
	
}
