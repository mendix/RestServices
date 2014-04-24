package communitycommons;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;

/**
 * 
 * @author mwe
 * @date 11-10-2012
 * 
 * This class can be used to allow more advanced logging, especially, to LOG short-running-conversations. 
 * 
 * This conversations leave the notions of INFO/DEBUG/TRACE and instead use notion of less details and more details. 
 * See @see section() and @see endSection() methods. See @see setBaseDetailVisibilityLevel about how much detail is visible
 * 
 * Loglines are marked with the conversation id, so loglines of a single conversation can be aggregated together. 
 * 
 * The ConversationLog has a playback notion, for example, if an exception occurs, all loglines, even the detailed ones which would not be visible otherwise, are displayed.
 * 
 * A typical logline will look like this:
 * 
 * 
 * MxSync - [sDvX][Thu 08:56:00]           starting module ... FAILED (in 20 ms. 3 hidden)
 *    ^        ^        ^            ^           ^                ^      ^          ^
 *    |        |        |            |           |                |      |          |
 * lognode     |     timestamp       |         LOG message        |      |    nr. of hidden details
 *             |                     |                            |      |
 *         conversation ID           |                          result   |
 *                                   |                                   |
 *                   indent, indicates the level of detail          timing behavior
 *                   
 *                   
 *                   
 */ 
public class ConversationLog
{
	private final ILogNode	log;
	private final int id;
	
	private static final AtomicInteger conversationNumber = new AtomicInteger();

	private enum LogType { SECTION_START, NORMAL, SECTION_END, WARNING, ERROR }

	private static final DateTimeFormatter dateFormat = DateTimeFormat.forPattern("HH:mm:ss"); //DateTimeFormat is thread safe, simpleDateFormat is not!
	
	private static class LogLine {
		final LogType	type;
		final String	msg;
		final long	time;
		final int	level;
		int	closed = -1;
		boolean	hasError;
		int	started;
		final Throwable	exception;

		LogLine(LogType type, String msg, int level, Throwable e) {
			this.type = type;
			this.msg = msg;
			this.time = System.currentTimeMillis();
			this.level = level;
			this.exception = e;
		}

		public LogLine(LogType type, String msg, int level) 
		{
			this(type, msg, level, null);
		}

		@Override
		public String toString() {
			return msg == null ? "(empty)" : msg.toString();
		}
	}
	
	private List<LogLine> messages = new ArrayList<LogLine>();
	private int	currentlevel = 0;
	private int flushedUntil = 0;
	private int	basevisiblelevel = 0;
	private boolean	closed = false;
	

	
	/**
	 * Create a new conversion
	 * @param M2ee loglevel to report to
	 */
	public ConversationLog(String lognode) {
		this.log = Core.getLogger(lognode);
		id = conversationNumber.incrementAndGet();
		
	}
	
	/**
	 * Create a new conversion
	 * @param M2ee loglevel to report to
	 * @param Log message of the first section that will be created
	 */
	public ConversationLog(String lognode, String firstSection) {
		this.log = Core.getLogger(lognode);
		id = conversationNumber.incrementAndGet();
		
		section(firstSection);
	}
	
	/**
	 * @return the Id of this converstion
	 */
	public int getId() {
		return id;
	}

	/**
	 * Starts a section. That is, first print the provided then increase the level of detail. 
	 * 
	 * For each section, timing behavior and result status will be measured. 
	 * 
	 * Note that all sections should be ended! @see endSection
	 * 
	 * If an exception is thrown, that ends the conversation entirely, this constraint can be dropped, 
	 * but in all other cases (no- or catched exceptions) all sections should be ended, probably by using finally.  
	 * 
	 * @param message to print
	 * @param optional message arguments
	 * @return
	 */
	public ConversationLog section(String msg, Object... args)
	{
		messages.add(new LogLine(LogType.SECTION_START, String.format(msg + " ... ", args), currentlevel));
		currentlevel  += 1;
		
		return this;
	}
	
	public ConversationLog endSection() {
		return endSection(null);		
	}
	
	/**
	 * Ends the current section, generates a report of the section (if visible). 
	 * @see section() about when and how to end sections 
	 * 
	 * @param The result message of this section to be reported back. Defaults to "DONE". 
	 * @return 
	 */
	public ConversationLog endSection(String result) {
		if (currentlevel < 1)
			warn("(ConversationLog too many endsection invocations)");
		currentlevel -= 1;
		
		LogLine l = new LogLine(LogType.SECTION_END, result, currentlevel);
		
		for (int i = messages.size() - 1; i >= 0; i--)
			if (messages.get(i).level == currentlevel && messages.get(i).type == LogType.SECTION_START) {
				messages.get(i).closed = messages.size();
				l.started = i;
				break;
			}
		
		messages.add(l);
		
		flushIfLastLineVisible();
		
		return this;
	}
	
	
	public void log(String msg, Object... args) {
		messages.add(new LogLine(LogType.NORMAL, String.format(msg, args), currentlevel));
		
		flushIfLastLineVisible();
	}

	public ConversationLog warn(String msg, Object... args) {
		return warn(null, msg, args);
	}
	
	public ConversationLog warn(Throwable e, String msg, Object... args) {
		messages.add(new LogLine(LogType.WARNING, String.format(msg, args), currentlevel, e));
		
		flushIfLastLineVisible();
		return this;
	}
	
	public void error(Throwable e) {
		error(e, e.getMessage());
	}
	
	public void error(String msg, Object... args) {
		error(null, msg, args);
	}
	
	public void error(Throwable e, String msg, Object... args) {
		messages.add(new LogLine(LogType.ERROR, String.format(msg, args), currentlevel, e));

		int minlevel = currentlevel;
		
		for (int i = messages.size() -1; i >= 0; i--) {
			LogLine line = messages.get(i);
			if (line.level <= minlevel) {
				if (line.hasError) //been there, done that, appereantly there ar multiple errors..
					break;
				line.hasError = true;
				
				minlevel = Math.min(minlevel, line.level); //once we seen a lower level, we do not want to go into higher levels anymor
			}
		}
		
		flush(); //always flush on exceptions
	}
	
	
	long lastResportedTime = 0;
	final static int REPORT_INTERVAL = 10;
	final static int PROGRESSBAR_WIDTH = 10;
	
	/**
	 * Reports progress about a batch. The total can default to zero if not provided. 
	 * This function is state-less, so many calls to it will not result in heap issues.
	 * 
	 * @param msg
	 * @param current
	 * @param total
	 */
	public void reportProgress(String msg, long current, long total) {
		long currentReportTime = System.currentTimeMillis();
		
		boolean report = (currentReportTime - lastResportedTime > REPORT_INTERVAL * 1000) || total == current; 
		
		if (report) {
			lastResportedTime = currentReportTime;
			
			if (total == 0 || total < current) 
				log.info(String.format("[%s| %d / ?] %s",
					StringUtils.leftPad("", (long) (PROGRESSBAR_WIDTH + 1), "_"),
					current, msg						
					));
			
			else {
				int chars = Math.round((current / total) * PROGRESSBAR_WIDTH);
			
				log.info(String.format("[%s%s| %d / %d] %s",
						StringUtils.leftPad("|", (long) chars, "="),
						StringUtils.leftPad("", (long) (PROGRESSBAR_WIDTH - chars), "_"),
						current, total, msg						
						));
			}
		}
	}

	public void reportProgress(String msg, float progress) {
		reportProgress(msg, Math.round(progress * 100), 100);
	}
	
	/**
	 * Sets the detail level of the current conversation. This determines how deep sections can be nested before becoming invisible in the LOG
	 * 
	 * If the detail level is for example 3, this means that the contents of the first 2 nested sections are visible. 
	 * 
	 * This holds only if the loglevel of the M2EE LOG is set to 'INFO'. 
	 * If the loglevel is set to 'DEBUG', the effective detail level is always 2 higher than the assigned level. 
	 * If the loglevel is set to 'TRACE', all loglines are always visible, regardless the assigned level. 
	 * 
	 * Use getDetailVisibilityLevel to get the effective visibility level. 
	 * 
	 * Furthermore, warnings and errors are always visible. 
	 *  
	 * @param detaillevel The detail level, defaults two 2. 
	 */
	public ConversationLog setBaseDetailVisibilityLevel(int detaillevel) {
		this.basevisiblelevel  = detaillevel;
		return this;
	}
	
	/**
	 * Returns the ACTIVE visibility level of this conversation. This depends on the base detail visibility level and the loglevel of the
	 * M2ee node. @see   setBaseDetailVisibilityLevel for more info. 
	 * @return
	 */
	public int getVisibleLogLegel() {
		 return log.isTraceEnabled() ? 1000 : log.isDebugEnabled() ? this.basevisiblelevel + 1 : this.basevisiblelevel;
	}
	
	private void flushIfLastLineVisible()
	{
		//flush if this is a visible section
		if (currentlevel <= getVisibleLogLegel())
			flush();
	}
	
	/**
	 * Force all current known loglines to be written to the M2ee LOG. 
	 * @return
	 */
	public ConversationLog flush() {
		int hidden = 0;
		int actuallevel = getVisibleLogLegel();
		
		while (flushedUntil < messages.size()) {
			LogLine line = messages.get(flushedUntil);
		
			switch (line.type) {
				case ERROR:
					writeToLog(line, (line.msg != null ? line.msg : line.exception != null ? line.exception.getMessage() : ""));
					break;
					
				case WARNING:
					writeToLog(line,(line.msg != null ? line.msg : line.exception != null ? line.exception.getMessage() : ""));
					break;
					
				case NORMAL:
					if (line.level <= actuallevel || line.hasError)
						writeToLog(line);
					else 
						hidden += 1;
					break;
				
				case SECTION_START:
					/**
					 * error INSIDE this section?
					 */
					if (line.hasError && flushedUntil + 1 < messages.size() /*avoid out of bounds*/) {  
						LogLine nextline = messages.get(flushedUntil + 1);
						
						//is the error ínside this section?
						if (nextline.hasError && nextline.type != LogType.SECTION_END)
							writeToLog(line);
					}
					
					/**
					 * visible level and not ended yet. We are flushing so we want to display that the section did start
					 */
					else if (line.level <= actuallevel && line.closed == -1)
						writeToLog(line);
					
					/**
					 * section is ended, but there are submessages and they are visible. Show begin
					 */
					else if (line.closed > flushedUntil + 1 && line.level < actuallevel)
						writeToLog(line);
					
					
					/** 
					 * we are flushing a subsection of the current section, that is, there are visible messages inside this section, 
					 * even if this section is not closed. Note that this only works because the following holds. 
					 * 
					 * - The SECTION is not closed yet (it would have been flushed earlier otherwise)
					 * - Something triggered a flush (ENDSECTION or ERROR) which is inside this section (it would have feen flushed earlier otherwise) 
					 *  
					 */
					else if (flushedUntil < messages.size() && line.level < actuallevel && line.closed == 0) 
						writeToLog(line);
					
					/**
					 * Not printing, report hidden
					 */
					else if (line.level >= actuallevel)
						hidden += 1;
					
					//all other cases, this line is written by end_section
					break;

				case SECTION_END:
					LogLine begin = this.messages.get(line.started);
					boolean outputline = false;
					
					/**
					 * begin section has error, generate report
					 */
					if (begin.hasError)
						outputline = true;
					
					/**
					 * visible, but empty section, generate report
					 */
					else if (line.level <= actuallevel)
						outputline = true;
					
					if (outputline) {
						String hiddenmsg = hidden == 0 ? "": String.format("%d hidden", hidden);
						String statusmsg = line.msg == null ? "DONE" : line.msg;
						long delta = line.time - begin.time;
						String msg = String.format("%s %s (in %d ms. %s)", begin.msg, statusmsg, delta, hiddenmsg);
						writeToLog(line, msg);
					}
					
					//reset hidden					
					if (line.level >= actuallevel) 
						hidden = 0; 
					
					break;
					
			}
			
			flushedUntil += 1;
		}
		
		return this;
	}

	private void writeToLog(LogLine line) {
		writeToLog(line, line.msg);
	}
	
	private void writeToLog(LogLine line, String msg)
	{
		String base = String.format("[%04d][%s]%s %s", 
				this.getId(), 
				dateFormat.print(line.time),  
				StringUtils.leftPad("", line.level * 4L, " "), 
				msg
		);
		
		switch(line.type) {
			case ERROR:
				log.error(base, line.exception);
				break;
			case NORMAL:
			case SECTION_END:
			case SECTION_START:
				log.info(base);
				break;
			case WARNING:
				log.warn(base, line.exception);
				break;
		}
		
		if (closed)
			log.warn(String.format("[%s] (Objection! Messages were added to the conversation after being closed!)", this.id));
	}
	
	/**
	 * Closes the conversation. This is a kind of assertion to see if your code properly ends all sections. Warnings are printed otherwise. 
	 */
	public void close() {
		
		if (flushedUntil < messages.size()) {
			flush();
		}
    if (currentlevel > 0)
   	  log.warn(String.format("[%04d] (Objection! Being destructed, but found %d unclosed sections. The conversation did not end normally?)", getId(), currentlevel));

    this.closed  = true;
	}
	
	@Override
	public String toString() {
		return messages.toString();
	}
	
	@Override
	protected void finalize() throws Throwable {
    try {
       close();
    } finally {
        super.finalize();
    }
}

	public int getCurrentLevel()
	{
		return currentlevel;
	}

}
