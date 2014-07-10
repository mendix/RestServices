package tests;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import restservices.RestServices;
import restservices.consume.RestConsumeException;
import restservices.consume.RestConsumer;
import restservices.proxies.HttpMethod;
import restservices.proxies.RequestResult;
import restservices.proxies.ResponseCode;
import restservices.util.Utils;
import tests.proxies.CTaskView;
import tests.proxies.Task;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

public class BasicTests extends TestBase {

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
		RequestResult xmlresult = RestConsumer.getObject(c, baseUrl + t.getNr().toString() + "?contenttype=xml", null);
		
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
		
		try {
			v = getTask(c2, t.getNr().toString(), null, ResponseCode.OK, 200);
			Assert.fail();
		}
		catch(RestConsumeException e) { 
			Assert.assertEquals(404L, e.getStatus());
		}
	}
	
	@Test
	public void simpleList() throws Exception {
		//count
		IContext c = Core.createSystemContext();
		JSONObject d = new JSONObject(RestConsumer.request(c, HttpMethod.GET, baseUrl +"?count", null, null, false).getResponseBody());
		Assert.assertEquals(0, d.getInt("count"));
				
		
		Task t1 = createTask(c, "Fetch milk", false);
		Task t2 = createTask(c, "Give it to the cat", true);
		Task t3 = createTask(c, "Make coffee", false);
		
		publishTask(c, t1, false);
		publishTask(c, t2, false);
		publishTask(c, t3, false);
		
		IContext c2 = Core.createSystemContext();
		
		//count
		d = new JSONObject(RestConsumer.request(c, HttpMethod.GET, baseUrl +"?count", null, null, false).getResponseBody());
		Assert.assertEquals(3, d.getInt("count"));
		
		//Test difference between include data and not include data
		JSONArray ar = new JSONArray(RestConsumer.request(c, HttpMethod.GET, baseUrl, null, null, false).getResponseBody());
		Assert.assertEquals(3, ar.length());
		Assert.assertEquals(ar.getString(0), baseUrl + t1.getNr());
		Assert.assertEquals(ar.getString(1), baseUrl + t2.getNr());
		Assert.assertEquals(ar.getString(2), baseUrl + t3.getNr());

		ar = new JSONArray(RestConsumer.request(c, HttpMethod.GET, baseUrl +"?data=true", null, null, false).getResponseBody());
		Assert.assertEquals(3, ar.length());
		Assert.assertEquals(ar.get(0) instanceof JSONObject, true);
		Assert.assertEquals(ar.get(1) instanceof JSONObject, true);
		Assert.assertEquals(ar.get(2) instanceof JSONObject, true);

		//Peform a get on the list
		
		List<IMendixObject> tasks = new ArrayList<IMendixObject>();
		IMendixObject firstResult = new CTaskView(c2).getMendixObject();
		
		tasks.clear();
		RestConsumer.getCollection(c2, baseUrl, tasks, firstResult);
		
		Assert.assertEquals(tasks.size(), 3);
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(0)).getDescription(), "Fetch milk");
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(1)).getDescription(), "Give it to the cat");
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(2)).getDescription(), "Make coffee");
		
		//perform a get + data
		tasks.clear();
		RestConsumer.getCollection(c2, baseUrl + "?data=true", tasks, firstResult);
		
		Assert.assertEquals(tasks.size(), 3);
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(0)).getDescription(), "Fetch milk");
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(1)).getDescription(), "Give it to the cat");
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(2)).getDescription(), "Make coffee");
		
		//disable service
		def.setEnableListing(false);
		def.commit();
		
		try {
			tasks.clear();
			RestConsumer.getCollection(c2, baseUrl, tasks, firstResult);
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
		
		//count
		d = new JSONObject(RestConsumer.request(c, HttpMethod.GET, baseUrl +"?count", null, null, false).getResponseBody());
		Assert.assertEquals(d.getInt("count"), 2);
		
		//check results
		tasks.clear();
		RestConsumer.getCollection(c2, baseUrl, tasks, firstResult);
		
		Assert.assertEquals(tasks.size(), 2);
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(0)).getDescription(), "Fetch milk");
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(1)).getDescription(), "Make coffee");
		
		//perform a get + data
		tasks.clear();
		RestConsumer.getCollection(c2, baseUrl + "?data=true", tasks, firstResult);
		
		Assert.assertEquals(tasks.size(), 2);
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(0)).getDescription(), "Fetch milk");
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(1)).getDescription(), "Make coffee");

		//use offset
		tasks.clear();
		RestConsumer.getCollection(c2, baseUrl + "?offset=0&limit=1", tasks, firstResult);
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(0)).getDescription(), "Fetch milk");

		tasks.clear();
		RestConsumer.getCollection(c2, baseUrl + "?offset=1&limit=2", tasks, firstResult);
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(0)).getDescription(), "Make coffee");

		tasks.clear();
		RestConsumer.getCollection(c2, baseUrl + "?offset=2&limit=2", tasks, firstResult);
		Assert.assertEquals(tasks.size(), 0);

		//use offset and data
		tasks.clear();
		RestConsumer.getCollection(c2, baseUrl + "?offset=0&limit=1&data=true", tasks, firstResult);
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(0)).getDescription(), "Fetch milk");

		tasks.clear();
		RestConsumer.getCollection(c2, baseUrl + "?offset=1&limit=2&data=true", tasks, firstResult);
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(0)).getDescription(), "Make coffee");

		tasks.clear();
		RestConsumer.getCollection(c2, baseUrl + "?offset=2&limit=2&data=true", tasks, firstResult);
		Assert.assertEquals(tasks.size(), 0);

		//delete the first item
		tasks.clear();
		t1.delete();
		publishTask(c, t1, true);
		
		RestConsumer.getCollection(c2, baseUrl, tasks, firstResult);
		Assert.assertEquals(1, tasks.size());
		Assert.assertEquals(CTaskView.initialize(c2, tasks.get(0)).getDescription(), "Make coffee");
		
	}
	
	@Test
	public void getFromIndex() throws Exception {
		def.setEnableChangeLog(true);
		def.commit();
		
		simpleGet();

	}

	@Test
	public void listFromIndex() throws Exception {
		def.setEnableChangeLog(true);
		def.setSourceConstraint("");
		def.commit();
		
		simpleList();
	}
	
	@Test
	public void listWithSmallBatchsize() throws Exception {
		int bs = RestServices.BATCHSIZE;
		
		try {
			RestServices.BATCHSIZE = 2;
			
			simpleList();

			tearDown();
			setup();

			listFromIndex();

			tearDown();
			setup();
			RestServices.BATCHSIZE = 1;
			
			simpleList();
			
			tearDown();
			setup();
			listFromIndex();
		}
		finally {
			RestServices.BATCHSIZE = bs;
		}
	}

	@Test
	public void testComplexKey() throws Exception {
		IContext c = Core.createSystemContext();
		def.setSourceKeyAttribute("Description");
		def.setUseStrictVersioning(false);
		def.setEnableCreate(true);
		def.setEnableUpdate(true);
		def.setEnableDelete(true);
		def.setEnableGet(true);
		def.setEnableListing(true);
		def.commit();
		
		String key = "http://www.nu.nl/bla?q=3&param=value;  !@#$%^&*()_-+={}|[]\"\\:;\'<>?,./~`\n\r\t\b\fENDOFKEY";
		String enc = Utils.urlEncode(key);
		
		CTaskView t = new CTaskView(c);
		t.setDescription(key);
		
		RestConsumer.postObject(c, baseUrl, t.getMendixObject(), t.getMendixObject());
		
		Assert.assertEquals(key, t.getDescription());
		//GET with wrong key
		//assertErrorcode(c, HttpMethod.GET, baseUrl + key, 404);
		
		//GET with correct key
		CTaskView copy = new CTaskView(c);
		RestConsumer.getObject(c, baseUrl + enc, copy.getMendixObject());
		Assert.assertEquals(key, copy.getDescription());
		
		//LIST
		JSONArray ar = new JSONArray(RestConsumer.request(c, HttpMethod.GET, baseUrl, null, null, false).getResponseBody());
		Assert.assertEquals(1, ar.length());
		Assert.assertEquals(baseUrl + enc, ar.getString(0));
		
		//PUT
		t.setCompleted(true);
		RestConsumer.putObject(c, baseUrl + enc, t.getMendixObject(), null);
		
		RestConsumer.getObject(c, baseUrl + enc, copy.getMendixObject());
		Assert.assertEquals(true, copy.getCompleted());
		Assert.assertEquals(key, copy.getDescription());
		
		//count, there should still be one
		ar = new JSONArray(RestConsumer.request(c, HttpMethod.GET, baseUrl, null, null, false).getResponseBody());
		Assert.assertEquals(1, ar.length());
		
		//DELETE
		RestConsumer.deleteObject(c, baseUrl + enc, null);
		
		//count
		ar = new JSONArray(RestConsumer.request(c, HttpMethod.GET, baseUrl, null, null, false).getResponseBody());
		Assert.assertEquals(0, ar.length());
	}

	@Test 
	public void nullStringTest() throws Exception{
		IContext c = Core.createSystemContext();
		IContext c2 = Core.createSystemContext();
		
		def.setSourceKeyAttribute("Nr");
		def.setUseStrictVersioning(false);
		def.setEnableCreate(true);
		def.setEnableUpdate(true);
		def.setEnableDelete(true);
		def.setEnableGet(true);
		def.setEnableListing(true);
		def.commit();
		
		Task t = new Task(c);
		t.setDescription("bla");
		t.commit();
		
		CTaskView copy = new CTaskView(c2);
		RestConsumer.getObject(c2, baseUrl + t.getNr(), copy.getMendixObject());
		Assert.assertEquals("bla", copy.getDescription());
		
		copy.setDescription(null);
		RestConsumer.putObject(c2, baseUrl + t.getNr(), copy.getMendixObject(), null);
		
		copy = new CTaskView(c2);
		
		RestConsumer.getObject(c2, baseUrl + t.getNr(), copy.getMendixObject());
		Assert.assertEquals(null, copy.getDescription());
		
	}
	
	/*
	 * GitHub issue #22
	 */
	@Test
	public void testThatDataServicePublishedWithSeviceNameContainingSlashesIsServedUnderPathIncludingSlashes() throws Exception{
		final String serviceName = "path/to/service";
		final String description = "bla";

		IContext serverContext = Core.createSystemContext();
		IContext clientContext = Core.createSystemContext();

		def.setName(serviceName);
		def.setSourceKeyAttribute("Nr");
		def.setUseStrictVersioning(false);
		def.setEnableCreate(false);
		def.setEnableUpdate(false);
		def.setEnableDelete(false);
		def.setEnableGet(true);
		def.setEnableListing(true);
		def.commit();
	
		Task t = new Task(serverContext);
		t.setDescription(description);
		t.commit();

		CTaskView copy = new CTaskView(clientContext);
		RestConsumer.getObject(clientContext, RestServices.getBaseUrl() + serviceName + '/' + t.getNr(), copy.getMendixObject());

		assertEquals(description, copy.getDescription());
		
		RequestResult response = RestConsumer.getObject(clientContext, RestServices.getBaseUrl() + serviceName + "?about", null);
		assertEquals(200, (int) response.getRawResponseCode());
		
		//valid JSON?
		new JSONObject(response.getResponseBody());
	}

}
