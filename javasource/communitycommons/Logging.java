package communitycommons;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import communitycommons.proxies.LogLevel;

public class Logging
{
	private static Map<String, Long> timers = new HashMap<String, Long>();

	public static void log(String lognode, LogLevel loglevel, String message)
	{
		log(lognode, loglevel, message, null);		
	}
	
	public static void log(String lognode, LogLevel loglevel, String message, Throwable e) {
		ILogNode logger = Core.getLogger(lognode);
		switch (loglevel) {
		case Critical:
			logger.critical(message,e);
			break;
		case Warning:
			logger.warn(message,e);
			break;
		case Debug:
			logger.debug(message);
			break;
		case Error:
			logger.error(message,e);
			break;
		case Info:
			logger.info(message);
			break;
		case Trace:
			logger.trace(message);
			break;			
		}
	}

	public static void simpleLog(String message)
	{
		Core.getLogger("Community_Commons").info(message);		
	}

	
	public static Long measureEnd(String timerName, LogLevel loglevel,
			String message)
	{
		Long cur = new Date().getTime();
		if (!timers.containsKey(timerName))
			throw new IllegalArgumentException();
		String time = String.format("%d", cur - timers.get(timerName));
		log("Utility_log", loglevel, "Timer " + timerName + " finished in " + time + " ms. " + message);
		return timers.get(timerName);
	}

	public static void measureStart(String timerName)
	{
		timers.put(timerName, new Date().getTime());
	}
}
