package tests;


import org.junit.Assert;
import org.junit.Test;

import restservices.RestServices;
import restservices.consume.RestConsumer;
import restservices.proxies.HttpMethod;
import restservices.publish.PublishedMicroflow;
import restservices.util.Utils;
import tests.proxies.ReplaceIn;
import tests.proxies.ReplaceOut;
import tests.proxies.TestFile;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;

import communitycommons.StringUtils;
import communitycommons.XPath;

public class ServiceTest extends TestBase {

	@Test
	public void testMfService() throws Exception {
		new PublishedMicroflow("Tests.ReplaceService", "*", "Search & Replace");
		
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
	
	@Test
	public void testFileTransfer() throws Exception {
		IContext c = Core.createSystemContext();
		try {
			new PublishedMicroflow("Tests.FileMultiplier", "*", "Multiplies the contents of a file");
			
			TestFile source = new TestFile(c);
			source.setMultiplier(2);
			StringUtils.stringToFile(c, "Yolo", source);
			source.commit();
			
			TestFile destination = new TestFile(c);
			
			String url = RestServices.getServiceUrl("FileMultiplier");
			RestConsumer.request(c, HttpMethod.POST, url, source.getMendixObject(), destination.getMendixObject(), true);
			
			Assert.assertEquals(2L, (long)destination.getMultiplier());
			Assert.assertEquals("YoloYolo", StringUtils.stringFromFile(c, destination));

			//request params should override
			RestConsumer.request(c, HttpMethod.POST, Utils.appendParamToUrl(url, TestFile.MemberNames.Multiplier.toString(), "3"), source.getMendixObject(), destination.getMendixObject(), true);
			
			Assert.assertEquals(3L, (long)destination.getMultiplier());
			Assert.assertEquals("YoloYoloYolo", StringUtils.stringFromFile(c, destination));

		}
		finally {
			XPath.create(c, TestFile.class).deleteAll();
		}
	}
}
