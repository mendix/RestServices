package restservices.util;

import java.io.OutputStream;
import java.util.Stack;

import org.apache.commons.lang3.StringEscapeUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import restservices.RestServices;

public class DataWriter {

	private static class State {
		public State()
		{
			// empty constructor
		}
		boolean hasSomething = false;
		boolean isArray = false;
		boolean isObject = false;
		boolean isKey = false;
		boolean isListItem = false;
		String key = null;
	}
	
	public static final int JSON = 0;
	public static final int XML = 1;
	public static final int HTML = 2;
	
	private int mode;
	private Stack<State> states = new Stack<State>();
	private OutputStream writer;
	
	public DataWriter(OutputStream writer, int mode) {
		this.mode = mode;
		this.writer = writer;
		states.push(new State()); //root state to avoid NPE's
	}
	
	public DataWriter array() {
		writeValueStart();
		states.push(new State());
		state().isArray = true;
		
		if (mode == JSON)
			write("[");
		else if (mode == HTML)
			write("<ol>");
		
		return this;
	}
	
	public DataWriter endArray() {
		writeValueEnd();
		
		assrt(state().isArray, "unexpected endArray");
		
		if (mode == JSON)
			write("]");
		else if (mode == HTML)
			write("</ol>");
		
		states.pop();
		return this;
	}
	
	public DataWriter object() {
		writeValueStart();
		
		states.push(new State());
		state().isObject = true;

		if (mode == JSON)
			write("{");
		else if (mode == HTML)
			write("\n<table class=\"table-nested-").write(states.size() % 4 == 0 ? "even" : "odd").write("\">");
		
		return this;
	}
	
	public DataWriter endObject() {
		writeValueEnd();
		assrt(state().isObject, "unexpected endArray");
		
		if (mode == JSON)
			write("}");
		else if (mode == HTML)
			write("\n</table>");

		states.pop();
		writeValueEnd();
		return this;
	}

	public DataWriter key(String keyName) {
		writeValueEnd();
		
		assrt(state().isObject, "Key can only be used in state 'beginObject'");
		writeValueStart();
		
		State s = new State();
		s.isKey = true;
		s.key = mode == XML ? keyName.replaceAll("[^a-zA-Z0-9_]", "_") : keyName;
		states.push(s);
		
		if (mode == JSON)
			write(JSONObject.quote(s.key)).write(":");
		else if (mode == XML)
			write("<").write(s.key).write(">");
		else if (mode == HTML)
			write("\n<tr><td>").write(StringEscapeUtils.escapeHtml4(s.key)).write("</td><td>");
		
		return this;
	}
	
	public DataWriter value(Object value) {
		if (value == null || value == JSONObject.NULL)
			writeString(null);
		else if (value instanceof String)
			writeString((String) value);
		else if (value instanceof JSONObject)
			writeJSONObject((JSONObject)value);
		else if (value instanceof JSONArray)
			writeJSONArray((JSONArray) value);
		else if (value instanceof Long)
			value((long)(Long) value);
		else if (value instanceof Double)
			value((double)(Double) value);
		else if (value instanceof Integer)
			value((long)(Integer) value);
		else if (value instanceof Float)
			value((double)(Float) value);
		else if (value instanceof Boolean)
			value((boolean)(Boolean)value);
		else
			assrt(false, "Expected String, Number, JSONObject or JSONArray");
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
				write(Utils.autoGenerateLink(StringEscapeUtils.escapeHtml4(value)));
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
			object();
			String[] names = JSONObject.getNames(json); //MWE json bug, empty object returns null instead of empty array...
			if (names != null) for(String key : names)
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
			array();
			for(int i = 0, l = json.length(); i < l; i++)
				value(json.get(i));
			endArray();
		}
		return this;
	}
	
	private void writeValueStart() {
		if (mode == JSON && (state().isArray || state().isObject) && state().hasSomething)
			write(",");
		state().hasSomething = true;
		
		if (state().isArray) {
			states.push(new State());
			state().isListItem = true;
			
			if (mode == XML)
				write("<item>");
			else if (mode == HTML)
				write("\n<li>");
		}
		
	}
	
	private void writeValueEnd() {
		if (state().isKey){
			if (mode == XML)
				write("</").write(state().key).write(">");
			else if (mode == HTML)
				write("</td></tr>");

			states.pop();
		}
		
		else if (state().isListItem) {
			if (mode == XML)
				write("</item>");
			if (mode == HTML)
				write("</li>");
			
			states.pop();
		}
	}

	private State state() {
		return states.peek();
	}
	
	private DataWriter write(String data) {
		try {
			this.writer.write(data.getBytes(RestServices.UTF8));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return this;
	}
	
	private void assrt(boolean value, String msg) {
		if (!value)
			throw new IllegalStateException(this.getClass().getName() + " " + msg);
	}
}
