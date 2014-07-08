package tests;


import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Test;

import restservices.RestServices;
import restservices.consume.RestConsumeException;
import restservices.consume.RestConsumer;
import restservices.proxies.HttpMethod;
import restservices.proxies.RequestResult;
import restservices.publish.MicroflowService;
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
		new MicroflowService("Tests.ReplaceService", "*", "Search & Replace");
		
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
	public void testMfServiceWithPathParams() throws Exception {
		String pathTemplate = "piet/{haystack}/{needle}";
		new MicroflowService("Tests.ReplaceService", "*", "Search & Replace", HttpMethod.PUT, pathTemplate);
		
		IContext c = Core.createSystemContext();
		ReplaceIn input = new ReplaceIn(c);
		
		String url = RestServices.getBaseUrl() + pathTemplate + "?replacement={replacement}";
		
		input.sethaystack("Yolo");
		input.setneedle("o");
		input.setreplacement("uu");
		
		ReplaceOut output = new ReplaceOut(c);
		RestConsumer.request(c, HttpMethod.PUT, url, input.getMendixObject(), output.getMendixObject(), false);
		Assert.assertEquals("Yuuluu", output.getresult());
		
		input.sethaystack("Yolo?x=&://%$_-%2f");
		input.setneedle("%");
		input.setreplacement("u%?&=u");
		
		output = new ReplaceOut(c);
		RestConsumer.request(c, HttpMethod.PUT, url, input.getMendixObject(), output.getMendixObject(), true);
		Assert.assertEquals("Yolo?x=&://u%?&=u$_-u%?&=u2f", output.getresult());
	}
	
	@Test
	public void testMfServiceWithPathParamsCaseSensitivity() throws Exception {
		String pathTemplate = "piet/{haystack}/{needle}";
		new MicroflowService("Tests.ReplaceService", "*", "Search & Replace", HttpMethod.PUT, pathTemplate);
		
		IContext c = Core.createSystemContext();
		ReplaceIn input = new ReplaceIn(c);
		
		String url = RestServices.getBaseUrl() + pathTemplate + "?replacement=UU";
		
		input.sethaystack("Yolo");
		input.setneedle("o");
		
		ReplaceOut output = new ReplaceOut(c);
		RestConsumer.request(c, HttpMethod.PUT, url, input.getMendixObject(), output.getMendixObject(), false);
		Assert.assertEquals("YUUlUU", output.getresult());
	}
	
	
	@Test
	public void testMfServiceWithPathParamsCaseSensitive() throws Exception {
		String pathTemplate = "piet/{haYstack}/{NEEDLE}-{repLacement}";
		new MicroflowService("Tests.ReplaceService", "*", "Search & Replace", HttpMethod.PUT, pathTemplate);
		
		IContext c = Core.createSystemContext();
		ReplaceIn input = new ReplaceIn(c);
		
		String url = RestServices.getBaseUrl() + pathTemplate;
		
		input.sethaystack("Yolo");
		input.setneedle("o");
		input.setreplacement("uu");
		
		ReplaceOut output = new ReplaceOut(c);
		RestConsumer.request(c, HttpMethod.PUT, url, input.getMendixObject(), output.getMendixObject(), false);
		Assert.assertEquals("Yuuluu", output.getresult());
	}
	
	@Test
	public void testMfServiceWithComplexParams() throws Exception {
		String pathTemplate = "piet/{haystack}/{needle}-{repLacement}";
		new MicroflowService("Tests.ReplaceService", "*", "Search & Replace", HttpMethod.GET, pathTemplate);
		
		IContext c = Core.createSystemContext();
		ReplaceIn input = new ReplaceIn(c);
		
		String url = RestServices.getBaseUrl() + pathTemplate;
		
		String key = "http://www.nu.nl/bla?q=3&param=value;  !@#$%^&*()_-+={}|[]\"\\:;\'<>?,./~`\n\r\t\b\fENDOFKEY";
		
		input.sethaystack(key+key);
		input.setneedle(key);
		input.setreplacement("uu?uu");
		
		ReplaceOut output = new ReplaceOut(c);
		RestConsumer.request(c, HttpMethod.GET, url, input.getMendixObject(), output.getMendixObject(), false);
		Assert.assertEquals("uu?uuuu?uu", output.getresult());
	}
	
	@Test
	public void testMfServiceWithoutParams() throws Exception {
		String pathTemplate = "piet/jan";
		new MicroflowService("Tests.CustomStatusService", "*", "Custom Status", HttpMethod.GET, "/" + pathTemplate);
		
		IContext c = Core.createSystemContext();
		
		String url = RestServices.getBaseUrl() + pathTemplate;
		
		RequestResult requestData = RestConsumer.request(c, HttpMethod.GET, url, null, null, false);
		Assert.assertEquals(202, (int) requestData.getRawResponseCode());
		
		try {
			requestData = RestConsumer.request(c, HttpMethod.PUT, url, null, null, false);
			Assert.fail();
		}
		catch(RestConsumeException e1) {
			Assert.assertEquals(HttpStatus.SC_NOT_FOUND, (int) e1.getStatus());
		}
	}
	
	@Test
	public void testMfServiceImpersonate() throws Exception {
		String testuser = getTestUser();
		
		new MicroflowService("Tests.GetCurrentUsername", "Tests.AuthenticateWithCustomHeader", "Search & Replace with impersonate");
		
		IContext c = Core.createSystemContext();
		
		String url = RestServices.getServiceUrl("GetCurrentUsername");

		try {
			RestConsumer.request(c, HttpMethod.GET, url, null, null, false);
			Assert.assertFalse(true);
		}
		catch(RestConsumeException e) {
			Assert.assertEquals(HttpStatus.SC_UNAUTHORIZED, e.getResponseData().getStatus());
		}
		
		try {
			RestConsumer.addHeaderToNextRequest("apikey", "nonsense");
			RestConsumer.request(c, HttpMethod.GET, url, null, null, false);
			Assert.assertFalse(true);
		}
		catch(RestConsumeException e) {
			Assert.assertEquals(e.getResponseData().getStatus(), HttpStatus.SC_UNAUTHORIZED);
		}

		RestConsumer.addHeaderToNextRequest("apikey", testuser);
		RequestResult resp = RestConsumer.request(c, HttpMethod.GET, url, null, null, false);
		Assert.assertEquals(resp.getResponseBody(), testuser);
	}
	
	@Test
	public void testFileTransfer() throws Exception {
		IContext c = Core.createSystemContext();
		try {
			new MicroflowService("Tests.FileMultiplier", "*", "Multiplies the contents of a file");
			String url = RestServices.getServiceUrl("FileMultiplier");
			
			TestFile source = new TestFile(c);
			source.setMultiplier(2);
			StringUtils.stringToFile(c, "Yolo", source);
			source.commit();
			
			TestFile destination = new TestFile(c);
			
			RestConsumer.request(c, HttpMethod.POST, url, source.getMendixObject(), destination.getMendixObject(), true);
			
			Assert.assertEquals("YoloYolo", StringUtils.stringFromFile(c, destination));

			//request params should override
			RestConsumer.request(c, HttpMethod.POST, Utils.appendParamToUrl(url, TestFile.MemberNames.Multiplier.toString(), "3"), source.getMendixObject(), destination.getMendixObject(), true);
			
			Assert.assertEquals("YoloYoloYolo", StringUtils.stringFromFile(c, destination));
			
			//do not use multipart but direct binary data
			URL u = new URL(Utils.appendParamToUrl(url, TestFile.MemberNames.Multiplier.toString(), "3"));
			HttpURLConnection con = (HttpURLConnection) u.openConnection();
			con.setDoOutput(true);
			con.setDoInput(true);
			con.setRequestMethod("POST");
			con.setRequestProperty("Connection", "Keep-Alive");
			con.setRequestProperty("Content-Type", RestServices.CONTENTTYPE_OCTET);
			con.connect();
			OutputStream out =  con.getOutputStream();
			IOUtils.copy(IOUtils.toInputStream("Yolo"), out);
			out.flush();
			out.close();
			InputStream in = con.getInputStream();
			List<String> lines = IOUtils.readLines(in);
			in.close();
			con.disconnect();
			Assert.assertEquals(1, lines.size());
			Assert.assertEquals("YoloYoloYolo", lines.get(0));
		}
		finally {
			XPath.create(c, TestFile.class).deleteAll();
		}
	}

	/*
	 * GitHub issue #21.
	 */
	@Test
	public void testPublishingMfWithTrailingSlashOnPathIsRetrievableWithoutTrailingSlash() throws Exception {
		String pathTemplate = "piet/";
		new MicroflowService("Tests.Dummy", "*", "", HttpMethod.GET, pathTemplate);

		IContext c = Core.createSystemContext();

		String url = RestServices.getBaseUrl() + pathTemplate.substring(0, pathTemplate.length() - 1);

		RequestResult response = RestConsumer.request(c, HttpMethod.GET, url, null, null, false);
		Assert.assertEquals((Integer) 200, response.getRawResponseCode());
	}

	/*
	 * GitHub issue #21.
	 */
	@Test
	public void testPublishingMfWithoutTrailingSlashOnPathIsRetrievableWithTrailingSlash() throws Exception {
		String pathTemplate = "piet";
		new MicroflowService("Tests.Dummy", "*", "", HttpMethod.GET, pathTemplate);

		IContext c = Core.createSystemContext();

		String url = RestServices.getBaseUrl() + pathTemplate + "/";

		RequestResult response = RestConsumer.request(c, HttpMethod.GET, url, null, null, false);
		Assert.assertEquals((Integer) 200, response.getRawResponseCode());
	}

}
