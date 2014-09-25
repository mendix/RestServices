package tests;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

import restservices.proxies.BooleanValue;
import restservices.util.Function;
import restservices.util.JsonDeserializer;
import restservices.util.JsonSerializer;
import restservices.util.Utils;
import tests.proxies.A;
import tests.proxies.B;
import tests.proxies.CustomBooleanTest;
import tests.proxies.Task;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;

public class SerializationTests extends TestBase {

	@Test
	public void testDeserializeCaseInsensitive() throws Exception {
		String json = "{ \"COMPLETED\" : \"true\", \"desCRIPTION\" : \"browNIE\" }";

		IContext c = Core.createSystemContext();
		Task task = new Task(c);
		
		JsonDeserializer.readJsonDataIntoMendixObject(c, new JSONObject(json), task.getMendixObject(), false);
		
		Assert.assertEquals(true, task.getCompleted());
		Assert.assertEquals("browNIE", task.getDescription());
	}
	
	@Test
	public void testDeserialize1() throws Exception {
		
		final IContext c = Core.createSystemContext();
		
		final JSONObject data = new JSONObject();
		
		data.put("_ID", "17");
		data.put("super_name", "34");
		
		JSONObject b1 = new JSONObject();
		b1.put("attr", "test1");
		
		JSONObject b2 = new JSONObject();
		b2.put("attr", "test2");
		
		JSONObject b3 = new JSONObject();
		b3.put("attr", "test3");
		
		JSONArray ar = new JSONArray();
		ar.put(b2);
		ar.put(b3);
		
		data.put("A_B", b1);
		data.put("_A_bs", ar);

		Utils.withSessionCache(c, new Function<Boolean>() {

			@Override
			public Boolean apply() throws Exception {
				A a = new A(c);
			
				JsonDeserializer.readJsonDataIntoMendixObject(c, data, a.getMendixObject(), false);
		
				Assert.assertEquals("17", a.get_id());
				Assert.assertEquals("test1", a.getA_B().getattr());
				Assert.assertEquals(2, a.get_A_Bs().size());
				Assert.assertEquals("test2", a.get_A_Bs().get(0).getattr());
				Assert.assertEquals("test3", a.get_A_Bs().get(1).getattr());
				return true;
			}
			
		});
		
	}
	
	@Test
	public void testDeserialize2() throws Exception {
		
		final IContext c = Core.createSystemContext();
		
		final JSONObject data = new JSONObject();
		
		data.put("ID", "17");
		data.put("super-name", "34");
		
		JSONObject b1 = new JSONObject();
		b1.put("attr", "test1");
		
		JSONObject b2 = new JSONObject();
		b2.put("attr", "test2");
		
		JSONObject b3 = new JSONObject();
		b3.put("attr", "test3");
		
		JSONArray ar = new JSONArray();
		ar.put(b2);
		ar.put(b3);
		
		data.put("a_B", b1);
		data.put("a$bs", ar);

		Utils.withSessionCache(c, new Function<Boolean>() {

			@Override
			public Boolean apply() throws Exception {
				A a = new A(c);
				JsonDeserializer.readJsonDataIntoMendixObject(c, data, a.getMendixObject(), false);
				
				Assert.assertEquals("17", a.get_id());
				Assert.assertEquals("34", a.getsuper_name());
				Assert.assertEquals("test1", a.getA_B().getattr());
				Assert.assertEquals(2, a.get_A_Bs().size());
				Assert.assertEquals("test2", a.get_A_Bs().get(0).getattr());
				Assert.assertEquals("test3", a.get_A_Bs().get(1).getattr());
				return true;
			}
		});
	}
	
	@Test
	public void testBooleanEnumSerialization() throws Exception {
		IContext c = Core.createSystemContext();
		CustomBooleanTest bt;
		
		//true
		bt = new CustomBooleanTest(c);
		JsonDeserializer.readJsonDataIntoMendixObject(c, new JSONObject("{test:true}"), bt.getMendixObject(), true);
		Assert.assertEquals(BooleanValue._true, bt.gettest());

		Assert.assertEquals(true, JsonSerializer.writeMendixObjectToJson(c, bt.getMendixObject()).getBoolean("test"));
		
		//false
		bt = new CustomBooleanTest(c);
		JsonDeserializer.readJsonDataIntoMendixObject(c, new JSONObject("{test:false}"), bt.getMendixObject(), true);
		Assert.assertEquals(BooleanValue._false, bt.gettest());

		Assert.assertEquals(false, JsonSerializer.writeMendixObjectToJson(c, bt.getMendixObject()).getBoolean("test"));

		//empty
		bt = new CustomBooleanTest(c);
		JsonDeserializer.readJsonDataIntoMendixObject(c, new JSONObject("{}"), bt.getMendixObject(), true);
		Assert.assertEquals(null, bt.gettest());

		Assert.assertEquals(false, JsonSerializer.writeMendixObjectToJson(c, bt.getMendixObject()).has("test"));
	}
	
	@Test
	public void testComplexDeserialization() throws Exception {
		IContext c = Core.createSystemContext();
		
		A a = new A(c);
		JsonDeserializer.readJsonDataIntoMendixObject(c, new JSONObject("{\"super name\":\"1\"}"), a.getMendixObject(), true);
		Assert.assertEquals("1", a.getsuper_name());

		a = new A(c);
		JsonDeserializer.readJsonDataIntoMendixObject(c, new JSONObject("{\"super$name\":\"1\"}"), a.getMendixObject(), true);
		Assert.assertEquals("1", a.getsuper_name());

		a = new A(c);
		JsonDeserializer.readJsonDataIntoMendixObject(c, new JSONObject("{\"super-name\":\"1\"}"), a.getMendixObject(), true);
		Assert.assertEquals("1", a.getsuper_name());
	}
	
	@Test
	public void testComplexSerialization() throws Exception {
		IContext c = Core.createSystemContext();
		
		A a = new A(c);
		
		a.set_id("bla");
		a.set_id_jsonkey("id");
		a.setsuper_name("blaa");
		a.setsuper_name_jsonkey(" a !@#$%");
		
		B b = new B(c);
		b.setattr("bl");
		a.setA_B(b);
		a.setA_B_jsonkey("$-");
		
		JSONObject res = JsonSerializer.writeMendixObjectToJson(c, a.getMendixObject());
		Assert.assertFalse(res.has("_id_jsonkey"));
		Assert.assertFalse(res.has("A_B_jsonkey"));
		Assert.assertEquals("bla", res.getString("id"));
		Assert.assertEquals("blaa", res.getString(" a !@#$%"));
		Assert.assertEquals("bl", res.getJSONObject("$-").getString("attr"));
	}
}
