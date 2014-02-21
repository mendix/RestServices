package tests;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;
import communitycommons.XPath;

import restservices.consume.ChangeFeedListener;
import restservices.consume.RestConsumer;
import restservices.proxies.HttpMethod;
import tests.proxies.Task;
import tests.proxies.TaskCopy;

public class ChangeTests extends TestBase{

	private static final String ONUPDATE = "Tests.OnUpdateTask";
	private static final String ONDELETE = "Tests.OnDeleteTask";
	
	private JSONArray getChangesJSON(IContext c, long since) throws JSONException, Exception {
		return new JSONArray(RestConsumer.request(c, HttpMethod.GET, baseUrl + "changes/list?since=" + since, null, null, false).getResponseBody());
	}
	
	@Test
	public void testChanges() throws Exception {
		def.setEnableChangeTracking(true);
		def.commit();
		
		IContext c = Core.createSystemContext();
		IContext c2 = Core.createSystemContext();
		
		Assert.assertEquals(0L, getChangesJSON(c2, 0).length());
		
		Task t1 = createTask(c, "milk", false);
		publishTask(c, t1, false);
		
		JSONArray changes = getChangesJSON(c2, 1);
		Assert.assertEquals(0L, changes.length());
		
		changes = getChangesJSON(c2, 0);
		Assert.assertEquals(1L, changes.length());
		
		publishTask(c, t1, false); //should not change anything

		changes = getChangesJSON(c2, 0);
		Assert.assertEquals(1L, changes.length());
		assertChange(changes.getJSONObject(0), t1.getNr(), false, "milk");
		
		t1.setDescription("karnemelk");
		publishTask(c, t1, false);

		changes = getChangesJSON(c2, 0);
		Assert.assertEquals(1L, changes.length());
		assertChange(changes.getJSONObject(0), t1.getNr(), false, "karnemelk");
		
		ChangeFeedListener.fetch(baseUrl, ONUPDATE, ONDELETE);
		Assert.assertEquals(1L, XPath.create(c2, TaskCopy.class).count());
		Assert.assertEquals(t1.getNr(), XPath.create(c2, TaskCopy.class).first().getNr());
		Assert.assertEquals("karnemelk", XPath.create(c2, TaskCopy.class).first().getDescription());
		
		
		Task t2 = createTask(c, "twix", false);
		publishTask(c, t2, false);

		changes = getChangesJSON(c2, 0);
		Assert.assertEquals(2L, changes.length());
		assertChange(changes.getJSONObject(0), t1.getNr(), false, "karnemelk");
		assertChange(changes.getJSONObject(1), t2.getNr(), false, "twix");

		//check since param
		changes = getChangesJSON(c2, changes.getJSONObject(0).getLong("rev"));
		Assert.assertEquals(1L, changes.length());
		assertChange(changes.getJSONObject(0), t2.getNr(), false, "twix");
		
		Task t3 = createTask(c, "dog", false);
		publishTask(c, t3, false);

		ChangeFeedListener.fetch(baseUrl, ONUPDATE, ONDELETE);
		Assert.assertEquals(3L, XPath.create(c2, TaskCopy.class).count());
		
		publishTask(c, t2, true);

		changes = getChangesJSON(c2, 0);
		Assert.assertEquals(2L, changes.length());
		assertChange(changes.getJSONObject(0), t1.getNr(), false, "karnemelk");
		assertChange(changes.getJSONObject(1), t3.getNr(), false, "dog");
		assertChange(changes.getJSONObject(2), t2.getNr(), true, null);

		//fetching should  now result in 2 items
		ChangeFeedListener.fetch(baseUrl, ONUPDATE, ONDELETE);
		Assert.assertEquals(2L, XPath.create(c2, TaskCopy.class).count());
		
		//if we reset the state
		ChangeFeedListener.resetState(baseUrl);

		//there should be nothing
		Assert.assertEquals(0L, XPath.create(c2, TaskCopy.class).count());
		
		//until we fetch again
		ChangeFeedListener.fetch(baseUrl, ONUPDATE, ONDELETE);
		Assert.assertEquals(2L, XPath.create(c2, TaskCopy.class).count());
		
		//if we delete all data
		XPath.create(c2, TaskCopy.class).deleteAll();
		Assert.assertEquals(0L, XPath.create(c2, TaskCopy.class).count());

		//and fetch there should be still nothing, the tracker doesn't know after all..
		ChangeFeedListener.fetch(baseUrl, ONUPDATE, ONDELETE);
		Assert.assertEquals(0L, XPath.create(c2, TaskCopy.class).count());

		//but if we reset as well
		ChangeFeedListener.resetState(baseUrl);
		Assert.assertEquals(0L, XPath.create(c2, TaskCopy.class).count());

		//then everything should come back :)
		ChangeFeedListener.fetch(baseUrl, ONUPDATE, ONDELETE);
		Assert.assertEquals(2L, XPath.create(c2, TaskCopy.class).count());
		
	}

	private void assertChange(JSONObject jsonObject, Long key, boolean deleted,
			String description) throws Exception {
		Assert.assertEquals((long) jsonObject.getLong("key"), (long) key);
		Assert.assertEquals((boolean) jsonObject.getBoolean("deleted"), deleted);
		
		if (!deleted) {
			Assert.assertEquals(jsonObject.getJSONObject("data").getString("Description"), description, "Invalid description");

			//check etag
			String etag = RestConsumer.request(Core.createSystemContext(), HttpMethod.GET, baseUrl + key, null, null, false).getETag();
			Assert.assertEquals(etag, jsonObject.getString("etag"), "Invalid etag");
		}
	}
}
