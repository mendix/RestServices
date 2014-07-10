package tests;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.junit.Assert;
import org.junit.Test;

import restservices.util.UriTemplate;
import restservices.util.Utils;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

public class UriTemplateTest {

	@Test
	public void tests() throws Exception {
		//test slashes / exact matching
		test("/nonfancy/path", "/nonfancy/path", true);
		test("/nonfancy/path", "/nonfancy/dir", false);
		test("/nonfancy/path/", "/nonfancy/path", true);
		test("nonfancy/path/", "/nonfancy/path", true);
		test("/nonfancy/path", "/nonfancy/path/", true);
		
		//test empty paths
		test("/", "", true);
		test("", "", true);
		test("", "/", true);
		test("/", "/", true);
		
		//test casing
		test("/nonFANCY/path", "/nonfancy/path", true);
		test("/nonfancy/path", "/nonFANCY/path", true);
		
		//test params
		test("/nonFANCY/{param}", "/nonfancy/path", ImmutableMap.of("param", "path"));
		test("/nonFANCY/{param}", "/nonfancy/PATH/", ImmutableMap.of("param", "PATH"));
		test("/nonFANCY/{param}", "/nonfancy/", false);

		test("/nonFANCY/{param}/bla", "/nonfancy/path/bla", ImmutableMap.of("param", "path"));
		test("/nonFANCY/{param}/bla", "/nonfancy/%7Bparam%7D/bla", ImmutableMap.of("param", "{param}"));
		test("/nonFANCY/{param}/bla", "/nonfancy/{param}/bla", ImmutableMap.of("param", "{param}"));
		test("/nonFANCY/{param1}-{param2}/bla", "/nonfancy/1-3/bla", ImmutableMap.of("param1", "1", "param2", "3"));
		
		//url decoding
		test("/nonFANCY/{param}/bla", "/nonfancy/path%2fpath/bla", ImmutableMap.of("param", "path/path"));
		
		String complex = "http://www.nu.nl/bla?q=3&param=value;  !@#$%^&*()_-+={}|[]\"\\:;\'<>?,./~`\n\r\t\b\fENDOFKEY";
		test("/x/{param1}", "/x/" + Utils.urlEncode(complex), ImmutableMap.of("param1", complex));
		test("/x/{param1}/{param2}", "/x/" + Utils.urlEncode(complex) + "/" + Utils.urlEncode(complex), ImmutableMap.of(
				"param1", complex, "param2", complex));
	}
	
	@Test
	public void testThatCreateURIfillsInParams() {
		String url = new UriTemplate("/nonFANCY/{param1}-{param2}/bla").createURI(ImmutableMap.of("param1", "abc/def", "param2", "BLA"));
		Assert.assertEquals("/nonFANCY/abc%2Fdef-BLA/bla", url);
	}
	
	@Test(expected=IllegalArgumentException.class)
	public void testThatCreateURIfailsOnMissingArguments() {
		new UriTemplate("/nonFANCY/{param1}-{param2}/bla").createURI(ImmutableMap.of("param1", "abc/def", "wrongArgument", "BLA"));
	}
	
	@Test(expected=IllegalArgumentException.class)
	public void testThatCreateURIfailsOnEmptyArguments() {
		new UriTemplate("/nonFANCY/{param1}/bla").createURI(ImmutableMap.of("param1", ""));
	}

	@Test(expected=IllegalArgumentException.class)
	public void testThatCreateURIfailsOnNullArguments() {
		Map<String, String> m = new HashMap<String, String>();
		m.put("param1", null);
		new UriTemplate("/nonFANCY/{param1}/bla").createURI(m);
	}

	private void test(String template, String testPath,
			ImmutableMap<String, String> expectedParams) {
		Map<String, String> params = Maps.newHashMap();
		
		Assert.assertEquals(true, new UriTemplate(template).match(testPath, params));
		Assert.assertEquals(expectedParams.size(), params.size());
		
		for(Entry<String, String> expected : expectedParams.entrySet()) {
			Assert.assertTrue(params.containsKey(expected.getKey()));
			Assert.assertEquals(expected.getValue(), params.get(expected.getKey()));
		}
	}

	private void test(String template, String testPath, boolean shouldMatch) {
		Assert.assertEquals(shouldMatch, new UriTemplate(template).match(testPath, new HashMap<String, String>()));
	}
	
	@Test
	public void testURIEnoding() {
		assertEquals("%20", Utils.urlEncode(" "));
		assertEquals("%2B", Utils.urlEncode("+"));
		
		assertEquals(" ", Utils.urlDecode("+"));
		assertEquals(" ", Utils.urlDecode("%20"));
		assertEquals("+", Utils.urlDecode("%2B"));
	}
}
