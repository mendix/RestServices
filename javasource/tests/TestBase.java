package tests;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

import restservices.RestServices;
import restservices.consume.ChangeLogListener;
import restservices.consume.RestConsumeException;
import restservices.consume.RestConsumer;
import restservices.proxies.HttpMethod;
import restservices.proxies.RequestResult;
import restservices.proxies.ResponseCode;
import restservices.proxies.DataServiceDefinition;
import restservices.publish.ChangeLogManager;
import restservices.publish.MicroflowService;
import system.proxies.User;
import system.proxies.UserRole;
import tests.proxies.CTaskView;
import tests.proxies.SecuredObject;
import tests.proxies.Task;
import tests.proxies.TaskCopy;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IContext;

import communitycommons.StringUtils;
import communitycommons.XPath;

public class TestBase {
	DataServiceDefinition def;
	String baseUrl;
	RequestResult lastRequestResult;
	String username;
	User user;
	static final String PASSWORD = "Password1!";

	@Before
	public void setup() throws CoreException {
		RestServices.clearServices();
		
		IContext c = Core.createSystemContext();
		XPath.create(c, Task.class).deleteAll();
		XPath.create(c, TaskCopy.class).deleteAll();
		XPath.create(c, SecuredObject.class).deleteAll();
		
		XPath.create(c, DataServiceDefinition.class).deleteAll();
		
		this.def = XPath.create(c, DataServiceDefinition.class).findOrCreateNoCommit(DataServiceDefinition.MemberNames.Name, "tasks");
		def.setEnableGet(true);
		def.setEnableListing(true);
		def.setAccessRole("*");
		def.setSourceEntity(Task.entityName);
		def.setSourceConstraint("");
		def.setSourceKeyAttribute(Task.MemberNames.Nr.toString());
		def.setOnPublishMicroflow("Tests.TaskToView");
		def.setOnUpdateMicroflow("Tests.ViewToTask");
		def.setEnableChangeLog(false);
		def.commit();
		
		this.baseUrl = RestServices.getAbsoluteUrl("tasks");
	}
	
	@After
	public void tearDown() throws CoreException {
		if (username != null) {
			XPath.create(Core.createSystemContext(), User.class).eq(User.MemberNames.Name, username).deleteAll();
			username = null;
			user = null;
		}
		ChangeLogListener.unfollow(baseUrl);
		RestServices.clearServices();
		def.delete();
		
		MicroflowService.clearMicroflowServices();
	}
	
	String getTestUser() throws CoreException {
		if (username == null){
			IContext c = Core.createSystemContext();
			User user = XPath.create(c, User.class).findOrCreate(
					User.MemberNames.Name, StringUtils.randomHash(),
					User.MemberNames.Password, PASSWORD);

			user.setUserRoles(XPath.create(c, UserRole.class).eq(UserRole.MemberNames.Name, "User").all());
			user.commit();
			this.username = user.getName();
			this.user = user;
		}
		return username;
	}
	
	Task createTask(IContext c, String description, boolean done) throws CoreException {
		Task t = new Task(c);
		t.setDescription(description);
		t.setCompleted(done);
		t.commit();
		return t;
	}
	
	CTaskView getTask(IContext c, String nr, String eTag, ResponseCode respCode, long status) throws Exception {
		CTaskView task = new CTaskView(c);
		RequestResult res = RestConsumer.getObject(c, baseUrl + nr, eTag, task.getMendixObject());
		this.lastRequestResult = res;
		
		Assert.assertEquals(respCode, res.getResponseCode());
		Assert.assertEquals(status, (long) res.getRawResponseCode());
		return task;
	}
	
	void publishTask(IContext c, Task t, boolean delete) throws CoreException {
		if (delete) {
			t.delete();
			ChangeLogManager.publishDelete(c, t.getMendixObject());
		}
		else {
			t.commit();
			ChangeLogManager.publishUpdate(c, t.getMendixObject());
		}
	}
	
	protected void assertErrorcode(IContext context, HttpMethod method, String url, int code) throws Exception {
		try {
			RestConsumer.request(context, method, url, null, null, false);
			Assert.fail();
		}
		catch(RestConsumeException e) {
			Assert.assertEquals(code, e.getStatus()); //Method  not allowed
		}
	}

}
