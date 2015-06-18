package tests;

import java.util.List;

import org.apache.commons.httpclient.HttpStatus;
import org.junit.Assert;
import org.junit.Test;

import restservices.RestServices;
import restservices.consume.RestConsumeException;
import restservices.consume.RestConsumer;
import restservices.proxies.DataServiceDefinition;
import restservices.proxies.HttpMethod;
import restservices.publish.MicroflowService;
import tests.proxies.SecuredObject;
import tests.proxies.SecuredObjectView;

import com.google.common.collect.Lists;
import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

import communitycommons.XPath;

public class SecurityTests extends TestBase {

    // Microflow service returns count of owned secured objects only
	@Test
	public void testMfSecurity() throws Exception {
		
		this.getTestUser();
		
		MicroflowService mfservice = new MicroflowService("Tests.SecuredObjectCount", "Administrator", HttpMethod.GET, "");
		String serviceurl = RestServices.getAbsoluteUrl("SecuredObjectCount");
		
		IContext c = Core.createSystemContext();
		SecuredObject s = new SecuredObject(c);
		s.setName("test");
		s.commit();
		
		SecuredObject s2 = new SecuredObject(c);
		s2.setName("test2");
		s2.setSecuredObject_User(this.user);
		s2.commit();
		
		//wrong role
		try {
			RestConsumer.addCredentialsToNextRequest(username, PASSWORD);
			RestConsumer.getObject(c, serviceurl, null).getRawResponseCode();
			Assert.assertFalse(true);
		}
		catch(RestConsumeException re) {
			Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, re.getStatus());
		}
		
		mfservice.unregister();
		
		//no credentials
		new MicroflowService("Tests.SecuredObjectCount", "User", HttpMethod.GET, "");
		try {
			RestConsumer.getObject(c, serviceurl, null).getRawResponseCode();
			Assert.assertFalse(true);
		}
		catch(RestConsumeException re) {
			Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, re.getStatus());
		}

		//wrong user
		try {
			RestConsumer.addCredentialsToNextRequest("nonsense", PASSWORD);
			RestConsumer.getObject(c, serviceurl, null).getRawResponseCode();
			Assert.assertFalse(true);
		}
		catch(RestConsumeException re) {
			Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, re.getStatus());
		}

		//wrong password
		try {
			RestConsumer.addCredentialsToNextRequest(username, "nonsense");
			RestConsumer.getObject(c, serviceurl, null).getRawResponseCode();
			Assert.assertFalse(true);
		}
		catch(RestConsumeException re) {
			Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, re.getStatus());
		}
		
		//correct credentials, security application should result in only 1 object
		RestConsumer.addCredentialsToNextRequest(username, PASSWORD);
		Assert.assertEquals("1", RestConsumer.getObject(c, serviceurl, null).getResponseBody());
	}
	
	//Data service returns own objects only
	@Test
	public void testDataService() throws Exception {
		String username = this.getTestUser();
		IContext c = Core.createSystemContext();
		
		DataServiceDefinition def = XPath.create(c, DataServiceDefinition.class).findOrCreateNoCommit(DataServiceDefinition.MemberNames.Name, "securedobjects");
		def.setEnableGet(true);
		def.setEnableListing(true);
		def.setAccessRole("User");
		def.setSourceEntity(SecuredObject.entityName);
		def.setSourceConstraint("");
		def.setSourceKeyAttribute(SecuredObject.MemberNames.Name.toString());
		def.setOnPublishMicroflow("Tests.SecuredObjectToSecuredObjectView");
		//def.setOnUpdateMicroflow("Tests.ViewToTask");
		def.setEnableChangeLog(false);
		def.commit();

		SecuredObject s = new SecuredObject(c);
		s.setName("test");
		s.commit();
		
		SecuredObject s2 = new SecuredObject(c);
		s2.setName("test2");
		s2.setSecuredObject_User(this.user);
		s2.setReadOnly(false);
		s2.setUnavailable(false);
		s2.commit();
		
		String serviceurl = RestServices.getAbsoluteUrl("securedobjects");
		
		IMendixObject first = Core.instantiate(c, SecuredObjectView.entityName);
		List<IMendixObject> results = Lists.newArrayList();
		
		try {
			RestConsumer.getCollection(c, serviceurl + "?data=true", results, first);
			Assert.fail();
		}
		catch (RestConsumeException re) {
			Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, re.getStatus());
		}
		
		RestConsumer.addCredentialsToNextRequest(username, PASSWORD);
		RestConsumer.getCollection(c, serviceurl + "?data=true", results, first);
		
		Assert.assertEquals(1, results.size());
		
		SecuredObjectView res = SecuredObjectView.initialize(c, results.get(0));
		Assert.assertEquals(res.getName(), "test2");
		Assert.assertEquals(res.getReadOnly(), true); //these values have been set to false, but since they shouldn't be published, they will still have the default value 'true' in our copy
		Assert.assertEquals(res.getUnavailable(), true); 
	}
	
	// TODO: What about readonly and unavailable attributes
}
