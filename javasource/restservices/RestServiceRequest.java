package restservices;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletOutputStream;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;

public class RestServiceRequest {
	public static enum ContentType { JSON, XML, HTML }

	private PublishedServiceDefinition service;
	private Request request;
	private Response response;
	private ContentType contentType;
	private IContext context;
	private ServletOutputStream writer;

	public RestServiceRequest(PublishedServiceDefinition service, Request request, Response response) throws IOException {
		this.service = service;
		this.request = request;
		this.response = response;
		this.context = Core.createSystemContext(); //TODO: should be based on user credentials if access was required?
		this.writer = response.getOutputStream();
	}
	
	public ContentType getContentType() {
		return this.contentType;
	}
	
	public RestServiceRequest write(String data) {
		try {
			this.writer.print(data);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} //TODO: force to UTF-8?
		return this;
	}

	public IContext getContext() {
		return this.context;
	}
}
