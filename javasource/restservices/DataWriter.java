package restservices;

import java.io.PrintWriter;
import java.util.Stack;

import org.apache.commons.lang3.StringEscapeUtils;
import org.json.JSONArray;
import org.json.JSONObject;

public class DataWriter {

	private static class State {
		boolean hasSomething = false;
		boolean isArray = false;
		boolean isObject = false;
		boolean isKey = false;
		String key = null;
	}
	
	public static final int JSON = 0;
	public static final int XML = 1;
	public static final int HTML = 2;
	
	private int mode;
	private Stack<State> states = new Stack<State>();
	private PrintWriter writer;
	
	public DataWriter(PrintWriter writer, int mode) {
		this.mode = mode;
		this.writer = writer;
	}
	
	public DataWriter beginArray() {
		writeValueStart();
		
		if (mode == JSON)
			write("[");
		else if (mode == HTML)
			write("<ol>");
		
		states.push(new State());
		state().isArray = true;
		return this;
	}
	
	public DataWriter endArray() {
		states.pop();
		
		if (mode == JSON)
			write("]");
		else if (mode == HTML)
			write("</ol>");
		
		return this;
	}
	
	public DataWriter beginObject() {
		writeValueStart();
		
		if (mode == JSON)
			write("{");
		else if (mode == HTML)
			write("<table>");
		
		states.push(new State());
		state().isObject = true;
		return this;
	}
	
	public DataWriter endObject() {
		if (mode == JSON)
			write("}");
		else if (mode == HTML)
			write("</table>");

		writeValueEnd();
		return this;
	}

	public DataWriter key(String keyName) {
		assrt(state().isObject, "Key can only be used in state 'beginObject'");
		writeValueStart();
		
		State s = new State();
		s.isKey = true;
		s.key = keyName;
		states.push(s);
		
		if (mode == JSON)
			write(JSONObject.quote(keyName)).write(":");
		else if (mode == XML)
			write("<").write(keyName).write(">");
		else if (mode == HTML)
			write("<tr><td>").write(StringEscapeUtils.escapeHtml4(keyName)).write("</td><td>");
		
		return this;
	}
	
	public DataWriter value(Object value) {
		if (value == null)
			writeString(null);
		else if (value instanceof String)
			writeString((String) value);
		else if (value instanceof JSONObject)
			writeJSONObject((JSONObject)value);
		else if (value instanceof JSONArray)
			writeJSONArray((JSONArray) value);
		
		assrt(false, "Expected String, JSONObject or JSONArray");
		return this;
	}
	
	private DataWriter writeString(String value) {
		writeValueStart();

		if (value == null) {
			if (mode == JSON)
				write("null");
			else if (mode == HTML)
				write("<p class='null'>&lt;none&gt;</p>");
		}
		
		else {
			if (mode == JSON)
				write(JSONObject.valueToString(value));
			else if (mode == XML)
				write(StringEscapeUtils.escapeXml(value));
			else if (mode == HTML)
				write(StringEscapeUtils.escapeHtml4(value));
		}	
		
		writeValueEnd();
		return this;
	}
	
	public DataWriter value(long value) {
		return value(Long.toString(value));
	}

	public DataWriter value(double value) {
		return value(Double.toString(value));
	}
	
	public DataWriter writeNull() {
		return value(null);
	}
	
	public DataWriter value(boolean value) {
		return value(value ? "true" : "false");
	}
	
	private DataWriter writeJSONObject(JSONObject json) {
		if (mode == JSON) {
			writeValueStart();
			write(json.toString(2));
			writeValueEnd();
		}
		else {
			beginObject();
			for(String key : JSONObject.getNames(json))
				key(key).value(json.get(key));
			endObject();
		}
		return this;
	}

	private DataWriter writeJSONArray(JSONArray json) {
		if (mode == JSON) {
			writeValueStart();
			write(json.toString(2));
			writeValueEnd();
		}
		else {
			beginArray();
			for(int i = 0, l = json.length(); i < l; i++)
				value(json.get(i));
			endArray();
		}
		return this;
	}
	
	private void writeValueStart() {
		if (mode == JSON && state().hasSomething)
			write(",");
		else if (mode == XML && state().isArray)
			write("<item>");
		else if (mode == HTML && state().isArray)
			write("<li>");
		
		state().hasSomething = true;
	}
	
	private void writeValueEnd() {
		if (state().isKey){
			if (mode == XML)
				write("</").write(state().key).write(">");
			else if (mode == HTML)
				write("</td></tr>");

			states.pop();
		}
		if (mode == XML && state().isArray)
			write("</item>");
		if (mode == XML && state().isArray)
			write("</li>");
	}

	private State state() {
		return states.peek();
	}
	
	private DataWriter write(String data) {
		this.writer.write(data);
		return this;
	}
	
	private void assrt(boolean value, String msg) {
		if (!value)
			throw new IllegalStateException(this.getClass().getName() + " " + msg);
	}
}
