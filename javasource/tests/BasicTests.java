package tests;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import restservices.RestServices;
import restservices.consume.RestConsumer;
import restservices.proxies.RequestResult;
import restservices.proxies.ResponseCode;
import restservices.proxies.ServiceDefinition;
import tests.proxies.CTaskView;
import tests.proxies.Task;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IContext;

import communitycommons.XPath;

public class BasicTests {

	private ServiceDefinition def;
	private String baseUrl;

	@Before
	public void setup() throws CoreException {
		XPath.create(Core.createSystemContext(), Task.class).deleteAll();
		
		this.def = XPath.create(Core.createSystemContext(), ServiceDefinition.class).findOrCreateNoCommit(ServiceDefinition.MemberNames.Name, "tasks");
		def.setAccessRole("*");
		def.setSourceEntity(Task.entityName);
		def.setSourceKeyAttribute(Task.MemberNames.Nr.toString());
		def.setOnPublishMicroflow("Tests.TaskToView");
		def.setEnableChangeTracking(false);
		def.commit();
		
		this.baseUrl = RestServices.getServiceUrl("tasks");
	}
	
	private Task createTask(IContext c, String description, boolean done) throws CoreException {
		Task t = new Task(c);
		t.setDescription(description);
		t.setCompleted(done);
		t.commit();
		return t;
	}
	
	@Test
	public void simpleGet() throws Exception {
		IContext c = Core.createSystemContext();
		Task t = createTask(c, "Fetch milk", false);
		
		IContext c2 = Core.createSystemContext();
		CTaskView v = new CTaskView(c2);
		RequestResult res = RestConsumer.getObject(c2, baseUrl + t.getNr(), null, v.getMendixObject());
		
		Assert.assertEquals(ResponseCode.OK, res.getResponseCode());
		Assert.assertEquals(200L, (long) res.getRawResponseCode());
		Assert.assertEquals("Fetch milk", v.getDescription());
		Assert.assertEquals(t.getNr(), v.getNr());
		Assert.assertEquals(false, v.getCompleted());

	}
	
	//@Test
	public void getFromIndex() throws Exception {
		def.setEnableChangeTracking(true);
		def.commit();
		
		IContext c = Core.createSystemContext();
		Task t = createTask(c, "Fetch milk", false);
		
		IContext c2 = Core.createSystemContext();
		CTaskView v = new CTaskView(c2);
		RequestResult res = RestConsumer.getObject(c2, baseUrl + t.getNr(), null, v.getMendixObject());
		
		Assert.assertEquals(ResponseCode.OK, res.getResponseCode());
		Assert.assertEquals(200L, (long) res.getRawResponseCode());
		Assert.assertEquals("Fetch milk", v.getDescription());
		Assert.assertEquals(t.getNr(), v.getNr());
		Assert.assertEquals(false, v.getCompleted());

	}
	
}
