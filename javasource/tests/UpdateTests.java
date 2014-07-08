package tests;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import restservices.consume.RestConsumeException;
import restservices.consume.RestConsumer;
import restservices.proxies.RequestResult;
import restservices.proxies.ResponseCode;
import restservices.proxies.RestServiceError;
import tests.proxies.CTaskView;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;

public class UpdateTests extends TestBase {

	@Test
	public void testPost() throws Exception {
		IContext c = Core.createSystemContext();
		
		CTaskView t = new CTaskView(c);
		t.setCompleted(true);
		t.setDescription("Brownie");

		try {
			RestConsumer.postObject(c, baseUrl, t.getMendixObject(), false);
			Assert.fail();
		}
		catch(RestConsumeException e) {
			Assert.assertEquals(405L, e.getStatus()); //Method  not allowed
		}
		
		def.setEnableCreate(true);
		def.commit();
		
		RequestResult response = RestConsumer.postObject(c, baseUrl, t.getMendixObject(), false);
		Assert.assertEquals(201L, (long) response.getRawResponseCode());
		
		String nr = new JSONObject(response.getResponseBody()).getString("Nr");
		Assert.assertTrue(nr != null && !nr.isEmpty());
		Assert.assertTrue(null != response.getETag());
		
		t = getTask(c, nr, null, ResponseCode.OK, 200); 
		Assert.assertEquals("Brownie", t.getDescription());
		Assert.assertEquals(true, t.getCompleted());
		
		
		t = getTask(c, nr, response.getETag(), ResponseCode.NotModified, 304);
		
		//update
		def.setUseStrictVersioning(true);
		def.commit();
		
		t.setCompleted(false);
		t.setDescription("Twix");
		
		try {
			response = RestConsumer.putObject(c, baseUrl, t.getMendixObject(), null);
			Assert.fail();
		}
		catch(RestConsumeException e) {
			Assert.assertEquals(405L, e.getStatus()); //Method  not allowed
		}
		
		def.setEnableUpdate(true);
		def.commit();
		
		try {
			response = RestConsumer.putObject(c, baseUrl + nr, t.getMendixObject(), null);
			Assert.fail();
		}
		catch(RestConsumeException e) {
			Assert.assertEquals(409L, e.getStatus()); //Conflicted
		}
		
		response = RestConsumer.putObject(c, baseUrl + nr, t.getMendixObject(), response.getETag());
		Assert.assertEquals(204L, (long) response.getRawResponseCode()); //No content
		Assert.assertTrue(null != response.getETag());
		
		//new ETag should result in no response
		t = getTask(c, nr, response.getETag(), ResponseCode.NotModified, 304);
		
		t = getTask(c, nr, null, ResponseCode.OK, 200);
		Assert.assertEquals(t.getDescription(), "Twix");
		Assert.assertEquals(false, t.getCompleted());
		
		//test exception handling
		def.setUseStrictVersioning(false);
		def.commit();
		
		t.setDescription("Exception");
		try {
			response = RestConsumer.putObject(c, baseUrl + nr, t.getMendixObject(), null);
			Assert.fail();
		} catch(RestConsumeException e) {
			Assert.assertEquals(500, e.getStatus());
			JSONObject result = new JSONObject(e.getResponseData().getBody());
			Assert.assertTrue(result.getString(RestServiceError.MemberNames.errorMessage.toString()).contains("internal server error"));
		}
		
		t.setDescription("WebserviceException");
		try {
			response = RestConsumer.putObject(c, baseUrl + nr, t.getMendixObject(), null);
			Assert.fail();
		} catch(RestConsumeException e) {
			Assert.assertEquals(400, e.getStatus()); 
			JSONObject result = new JSONObject(e.getResponseData().getBody());
			Assert.assertEquals(result.getString(RestServiceError.MemberNames.errorMessage.toString()), "Invalid input");
		}
		
		def.setUseStrictVersioning(true);
		def.commit();
		
		//delete
		try {
			response = RestConsumer.deleteObject(c, baseUrl + nr, null);
			Assert.fail();
		}
		catch(RestConsumeException e) {
			Assert.assertEquals(405L, e.getStatus()); //Method  not allowed
		}
		
		
		def.setEnableDelete(true);
		def.commit();
		try {
			response = RestConsumer.deleteObject(c, baseUrl + nr, "blabla");
			Assert.fail();
		}
		catch(RestConsumeException e) {
			Assert.assertEquals(409L, e.getStatus()); //Conflict
		}
		
		response = RestConsumer.deleteObject(c, baseUrl + nr, response.getETag());
		Assert.assertEquals(204L, (long)response.getRawResponseCode());
		
		try {
			getTask(c, nr, null, ResponseCode.OK, 200);
			Assert.fail();
		}
		catch(RestConsumeException e) {
			Assert.assertEquals(404L, e.getStatus()); //Not available anymore
		}
	}
}
