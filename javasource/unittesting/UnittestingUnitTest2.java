package unittesting;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class UnittestingUnitTest2 extends AbstractUnitTest
{
	
	@Before
	public void setup() throws InterruptedException {
		Thread.sleep(500);
	}
	
	@After
	public void tearDown() throws InterruptedException {
		Thread.sleep(500);
	}
	
	@Test
	public void evenMoreUnitTests() throws InterruptedException {
		this.startTimeMeasure();
		
		this.reportStep("By inheriting from AbstractUnitTest some utility methods are provided and time can be tracted in a more reliable way (without counting setup and teardown)");
		Thread.sleep(1000);
		
		assertTrue(true);
		
		this.endTimeMeasure();
	}
	
	
}
