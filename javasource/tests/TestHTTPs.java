package tests;

import org.junit.Assert;
import org.junit.Test;

import restservices.consume.RestConsumer;

import com.mendix.core.Core;

public class TestHTTPs {
	
	@Test
	public void getMendix() throws Exception {
		Assert.assertEquals(200L,(long) RestConsumer.getObject(Core.createSystemContext(), "https://www.mendix.com", null).getRawResponseCode());
	}
	
	
}
