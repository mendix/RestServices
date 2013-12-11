package unittesting;


public class AbstractUnitTest
{
	private static long	startTime;
	private static long endTime;

	public static long getTestRunTime() {
		return endTime - startTime;
	}
	
	public void startTimeMeasure() {
		startTime = System.currentTimeMillis();
	}
	
	public void endTimeMeasure() {
		endTime = System.currentTimeMillis();
	}
	
	public void reportStep(String lastStep1)
	{
		TestManager.instance().reportStep(lastStep1);
	}
	
}
