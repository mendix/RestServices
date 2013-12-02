package restservices;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import javax.servlet.ServletOutputStream;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.json.JSONWriter;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;

public class RestServiceRequest {
	public static enum ContentType { JSON, XML, HTML }

	private PublishedService service;
	Request request;
	Response response;
	private ContentType contentType = ContentType.JSON;
	private IContext context;
	private PrintWriter writer;
	protected JSONWriter jsonwriter;

	public RestServiceRequest(PublishedService service, Request request, Response response) {
		this.service = service;
		this.request = request;
		this.response = response;
		this.context = Core.createSystemContext(); //TODO: should be based on user credentials if access was required?
		
		determineContentType();
		this.setResponseContentType();

		try {
			this.writer =new PrintWriter(response.getOutputStream());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		this.jsonwriter = new JSONWriter(writer);
	}

	private void determineContentType() {
		if (request.getParameter(RestServices.CONTENTTYPE_PARAM) != null)
			this.contentType = ContentType.valueOf(request.getParameter(RestServices.CONTENTTYPE_PARAM).toUpperCase());
		else {
			String ct = request.getHeader(RestServices.ACCEPT_HEADER);
			if (ct == null)
				return;
			else if (ct.contains("text/json"))
				this.contentType = ContentType.JSON;
			else if (ct.contains("html"))
				this.contentType = ContentType.HTML;
			else if (ct.contains("xml")) 
				this.contentType = ContentType.XML;
		}
	}
	
	private void setResponseContentType() {
		this.response.setContentType("text/" + this.contentType.toString().toLowerCase()+ "; charset=UTF-8");
	}
	
	public ContentType getContentType() {
		return this.contentType;
	}
	
	public RestServiceRequest write(String data) {
		this.writer.print(data);
		return this;
	}

	public IContext getContext() {
		return this.context;
	}

	public void close() {
		this.writer.close();
	}

	public void startHTMLDoc() {
		this.write("<!DOCTYPE HTML><html><head/><body>");		
	}


	public void endHTMLDoc() {
		this.write("</body></html>");
	}

	public void startXMLDoc() {
		this.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
	}

	public void setStatus(int status) {
		response.setStatus(status);
	}

	public String autoGenerateLink(String value) {
		if (value != null && (value.startsWith("http://") || value.startsWith("https://")))
			return "<a href='"+ value+ "'>" + value+ "</a>";
		return value;
	}
}
