package tests;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

import restservices.RestServices;
import restservices.consume.RestConsumer;
import restservices.proxies.RequestResult;
import restservices.proxies.ResponseCode;
import restservices.proxies.ServiceDefinition;
import restservices.publish.ChangeManager;
import system.proxies.User;
import system.proxies.UserRole;
import tests.proxies.CTaskView;
import tests.proxies.Task;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IContext;
import communitycommons.StringUtils;
import communitycommons.XPath;

public class TestBase {
	ServiceDefinition def;
	String baseUrl;
	RequestResult lastRequestResult;
	String username;

	@Before
	public void setup() throws CoreException {
		IContext c = Core.createSystemContext();
		XPath.create(c, Task.class).deleteAll();
		
		XPath.create(c, ServiceDefinition.class).eq(ServiceDefinition.MemberNames.Name, "tasks" ).deleteAll();
		
		this.def = XPath.create(c, ServiceDefinition.class).findOrCreateNoCommit(ServiceDefinition.MemberNames.Name, "tasks");
		def.setEnableGet(true);
		def.setEnableListing(true);
		def.setAccessRole("*");
		def.setSourceEntity(Task.entityName);
		def.setSourceConstraint("");
		def.setSourceKeyAttribute(Task.MemberNames.Nr.toString());
		def.setOnPublishMicroflow("Tests.TaskToView");
		def.setOnUpdateMicroflow("Tests.ViewToTask");
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
	
	String getTestUser() throws CoreException {
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
			ChangeManager.publishDelete(c, t.getMendixObject());
		}
		else {
			t.commit();
			ChangeManager.publishUpdate(c, t.getMendixObject());
		}
	}
	

}
