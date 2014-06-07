package restservices.publish;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.HttpStatus;

import restservices.RestServices;
import restservices.util.DataWriter;
import restservices.util.Utils;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.ISession;
import com.mendix.systemwideinterfaces.core.IUser;

import communitycommons.StringUtils;

public class RestServiceRequest {
	public static enum ResponseType { JSON, XML, HTML, PLAIN, BINARY }
	public static enum RequestContentType { JSON, FORMENCODED, MULTIPART, OTHER }

	private static final ThreadLocal<RestServiceRequest> currentRequest = new ThreadLocal<RestServiceRequest>();
	
	HttpServletRequest request;
	HttpServletResponse response;
	private ResponseType responseContentType = ResponseType.JSON;
	private RequestContentType requestContentType = RequestContentType.OTHER;
	private IContext context;
	protected DataWriter datawriter;
	private boolean autoLogout;
	private ISession activeSession;

	public RestServiceRequest(HttpServletRequest request, HttpServletResponse response) {
		this.request = request;
		this.response = response;
		
		this.requestContentType = determineRequestContentType(request);
		this.responseContentType = determineResponseContentType(request);

		try {
			this.datawriter = new DataWriter(response.getOutputStream(), responseContentType == ResponseType.HTML ? DataWriter.HTML : responseContentType == ResponseType.XML ? DataWriter.XML : DataWriter.JSON);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		
		currentRequest.set(this);
	}

	boolean authenticate(String role, ISession existingSession) {
		if ("*".equals(role)) {
			this.context = Core.createSystemContext();
			return true;
		}

		String authHeader = request.getHeader(RestServices.HEADER_AUTHORIZATION);
		String username = null;
		String password = null;
		ISession session = null;
		
		if (authHeader != null && authHeader.trim().startsWith(RestServices.BASIC_AUTHENTICATION)) {
			String base64 = StringUtils.base64Decode(authHeader.trim().substring(RestServices.BASIC_AUTHENTICATION.length()).trim());
			String[] parts = base64.split(":");
			username = parts[0];
			password = parts[1];
		}
		
		try {
			//Check credentials provided by request
			if (username != null) {
				session = Core.login(username, password);
				if (session == null) {
					setStatus(HttpStatus.SC_UNAUTHORIZED);
					write("Invalid credentials");
					return false;
				}
				
				//same user as the one in the current session? recylcle the session
				if (existingSession != null && session.getId().equals(existingSession.getId()) && existingSession.getUser().getName().equals(session.getUser().getName())) {
					Core.logout(session);
					session = existingSession;
				}
				else
					this.autoLogout = true;
			}
			
			//check session from cookies
			else if (existingSession != null)
				session = existingSession;
				
			//session found?
			if (session != null && session.getUser() != null && session.getUser().getUserRoleNames().contains(role)) {
				this.context = session.createContext().getSudoContext();
				this.activeSession = session;
				return true;
			}

		}
		catch(Exception e) {
			RestServices.LOGPUBLISH.warn("Failed to authenticate '" + username + "'" + e.getMessage(), e);
		}
		
		return false;
	}

	private RequestContentType determineRequestContentType(HttpServletRequest request) {
		String ct = request.getHeader(RestServices.HEADER_CONTENTTYPE);
		if (ct == null) 
			return RequestContentType.OTHER;
		if (ct.contains("text/json"))
			return RequestContentType.JSON;
		else if (ct.contains(RestServices.CONTENTTYPE_FORMENCODED))
			return RequestContentType.FORMENCODED;
		else if (ct.contains(RestServices.CONTENTTYPE_MULTIPART))
			return RequestContentType.MULTIPART;
		else 
			return RequestContentType.OTHER;
		
	}

	private ResponseType determineResponseContentType(HttpServletRequest request) {
		String ct = request.getParameter(RestServices.PARAM_CONTENTTYPE);
		if (ct == null)
			ct = request.getHeader(RestServices.HEADER_ACCEPT);
		if (ct != null) {
			if (ct.contains("text/json"))
				return ResponseType.JSON;
			if (ct.contains("html"))
				return ResponseType.HTML;
			if (ct.contains("xml")) 
				return ResponseType.XML;
		}
		return ResponseType.JSON; //Not set, fall back to default json 
	}
	
	public void setResponseContentType(ResponseType responseType) {
		switch (responseType) {
		case HTML:
			response.setContentType("text/html;charset=UTF-8");
			break;
		case JSON:
			response.setContentType("application/json;charset=UTF-8");
			break;
		case PLAIN:
			response.setContentType("text/plain;charset=UTF-8");
			break;
		case XML:
			response.setContentType("text/xml;charset=UTF-8");
			break;
		case BINARY:
			response.setContentType(RestServices.CONTENTTYPE_OCTET);
			break;
		default:
			throw new IllegalStateException();
		}
		
	}
	
	public ResponseType getResponseContentType() {
		return this.responseContentType;
	}
	
	public RequestContentType getRequestContentType() {
		return this.requestContentType;
	}
	
	public RestServiceRequest write(String data) {
		try {
			this.response.getOutputStream().write(data.getBytes(RestServices.UTF8));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return this;
	}

	public IContext getContext() {
		return this.context;
	}

	public void close() {
		try {
			this.response.getOutputStream().close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void startHTMLDoc() {
		this.write("<!DOCTYPE HTML><html><head><style>" + RestServices.STYLESHEET + "</style><head><body>");		
	}


	private void endHTMLDoc() {
		String url = Utils.getRequestUrl(request);
		this.write("<p><center><small>View as: <a href='")
			.write(Utils.appendParamToUrl(url, "contenttype", "xml"))
			.write("'>XML</a> <a href='")
			.write(Utils.appendParamToUrl(url, "contenttype", "json"))
			.write("'>JSON</a></small></center></p>");
		this.write("<hr /><p><center><small>Generated by the <a href='https://github.com/mendix/RestServices' target='_blank'>RestServices</a> module (v")
			.write(RestServices.VERSION)
			.write("). Powered by Mendix.</small></center></body></html>");
	}

	private void startXMLDoc() {
		this.write("<?xml version=\"1.0\" encoding=\"utf-8\"?><response>");
	}
	
	private void endXMLDoc() {
		this.write("</response>");
	}

	public void setStatus(int status) {
		response.setStatus(status);
	}

	public String getETag() {
		return request.getHeader(RestServices.HEADER_IFNONEMATCH);
	}

	public void dispose() {
		if (autoLogout)
			Core.logout(this.activeSession);
		clearCurrentRequest();
	}
	
	public IUser getCurrentUser() {
		return activeSession.getUser();
	}

	public void startDoc() {
		setResponseContentType(responseContentType);
		if (getResponseContentType() == ResponseType.HTML) { 
			startHTMLDoc();
		}
		else if (getResponseContentType() == ResponseType.XML) {
			startXMLDoc();
		}
	}

	public void endDoc() {
		if (getResponseContentType() == ResponseType.HTML) 
			endHTMLDoc();
		else if (getResponseContentType() == ResponseType.XML)
			endXMLDoc();
		close();
	}

	public String getRequestParameter(String param, String defaultValue) {
		String result = request.getParameter(param);
		return result == null ? defaultValue : result;
	}
	
	public static void clearCurrentRequest() {
		currentRequest.set(null);
	}

	public static String getRequestHeader(String headerName) {
		RestServiceRequest current = currentRequest.get();
		if (current == null)
			throw new IllegalStateException("Not handling a request currently");

		return current.request.getHeader(headerName);
	}

	public static void setResponseHeader(String headerName, String value) {
		RestServiceRequest current = currentRequest.get();
		if (current == null)
			throw new IllegalStateException("Not handling a request currently");

		current.response.setHeader(headerName, value);
	}
}
