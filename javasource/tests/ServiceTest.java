package tests;


import org.junit.Assert;
import org.junit.Test;

import restservices.RestServices;
import restservices.consume.RestConsumer;
import restservices.proxies.HttpMethod;
import restservices.publish.PublishedMicroflow;
import tests.proxies.ReplaceIn;
import tests.proxies.ReplaceOut;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;

public class ServiceTest extends TestBase {

	@Test
	public void testMfService() throws Exception {
		new PublishedMicroflow("Tests.ReplaceService", "*");
		
		IContext c = Core.createSystemContext();
		ReplaceIn input = new ReplaceIn(c);
		
		String url = RestServices.getServiceUrl("ReplaceService");
		
		input.sethaystack("Yolo");
		input.setneedle("o");
		input.setreplacement("uu");
		
		ReplaceOut output = new ReplaceOut(c);
		RestConsumer.request(c, HttpMethod.POST, url, input.getMendixObject(), output.getMendixObject(), false);
		Assert.assertEquals("Yuuluu", output.getresult());

		output = new ReplaceOut(c);
		RestConsumer.request(c, HttpMethod.GET, url, input.getMendixObject(), output.getMendixObject(), false);
		Assert.assertEquals("Yuuluu", output.getresult());

		
		output = new ReplaceOut(c);
		RestConsumer.request(c, HttpMethod.POST, url, input.getMendixObject(), output.getMendixObject(), true);
		Assert.assertEquals("Yuuluu", output.getresult());

		
		output = new ReplaceOut(c);
		RestConsumer.request(c, HttpMethod.GET, url, input.getMendixObject(), output.getMendixObject(), true);
		Assert.assertEquals("Yuuluu", output.getresult());
	}
}
