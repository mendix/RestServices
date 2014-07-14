package restservices.publish;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.HttpStatus;

import restservices.RestServices;
import restservices.proxies.Cookie;
import restservices.util.DataWriter;
import restservices.util.Function;
import restservices.util.Utils;
import system.proxies.User;

import com.google.common.base.Preconditions;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.ISession;
import com.mendix.systemwideinterfaces.core.IUser;
import communitycommons.StringUtils;

public class RestServiceRequest {
	public static enum ResponseType { JSON, XML, HTML, PLAIN, BINARY }
	public static enum RequestContentType { JSON, FORMENCODED, MULTIPART, OTHER }

	HttpServletRequest request;
	HttpServletResponse response;
	private ResponseType responseContentType;
	private RequestContentType requestContentType;
	private IContext context;
	protected DataWriter datawriter;
	private boolean autoLogout;
	private ISession activeSession;
	private IMxRuntimeResponse mxresponse;
	private String relpath;

	public RestServiceRequest(HttpServletRequest request, HttpServletResponse response, IMxRuntimeResponse mxresponse, String relpath) {
		this.request = request;
		this.response = response;
		this.mxresponse = mxresponse;
		this.relpath = relpath;
		
		this.requestContentType = determineRequestContentType(request);
		this.responseContentType = determineResponseContentType(request);

		try {
			this.datawriter = new DataWriter(response.getOutputStream(), responseContentType == ResponseType.HTML ? DataWriter.HTML : responseContentType == ResponseType.XML ? DataWriter.XML : DataWriter.JSON);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void setContext(IContext context) {
		this.context = context;
	}

	public IContext getContext() {
		return this.context;
	}

	boolean authenticate(String roleOrMicroflow, ISession existingSession) throws Exception {
		if ("*".equals(roleOrMicroflow)) {
			setContext(Core.createSystemContext());
			return true;
		}

		else if (roleOrMicroflow.indexOf('.') != -1) //Modeler forbids dots in userrole names, while microflow names always are a qualified name
			return authenticateWithMicroflow(roleOrMicroflow);
		
		else
			return authenticateWithCredentials(roleOrMicroflow, existingSession);
	}

	private boolean authenticateWithCredentials(String role,
			ISession existingSession)  throws Exception {
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
				try {
					session = Core.login(username, password);
				}
				catch (Exception e) { //can throw both authentication exceptions and core runtime exceptions, depending on whether the password or the username is wrong...
					//Invalid credentials
				}
				if (session == null) {
					RestServices.LOGPUBLISH.warn("Invalid credentials for user '" + username + "'");
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
				setContext(session.createContext());
				this.activeSession = session;
				return true;
			}

		}
		catch(Exception e) {
			RestServices.LOGPUBLISH.warn("Failed to authenticate '" + username + "'" + e.getMessage(), e);
			throw e;
		}
		
		return false;
	}

	
	private static final Map<String, Object> EMPTY_MAP = new HashMap<String, Object>();
	
	private boolean authenticateWithMicroflow(final String microflowName) throws Exception {
		
		
		try {
			
			// Create a context and transaction, so that headers can be inspected during the execution of the authorization microflow.
			final IContext c = Core.createSystemContext();
			this.setContext(c);
			
			IMendixObject userobject = withTransaction(new Function<IMendixObject>() {

				@Override
				public IMendixObject apply() throws CoreException {
					return Core.execute(c, microflowName, EMPTY_MAP);
				}
			
			});
			
			this.setContext(null); //authentication was in system context, but execution will be in user context
			
			if (userobject == null) 
				return false;
			
			String username = (String) userobject.getValue(c, User.MemberNames.Name.toString());
			
			if (username == null || username.isEmpty())
				throw new IllegalStateException("Trying to authenticate a user without a name"); //yes, this actually went wrong once during testing, due to a broken DB record...
			
			IUser user = Core.getUser(c, username);
			ISession session = Core.initializeSession(user, null);
			
			this.autoLogout = true;
			this.activeSession = session;

			this.setContext(session.createContext());
			return true;

		} catch (Exception e) {
			RestServices.LOGPUBLISH.warn("Failed to authenticate request using microflow '" + microflowName + "', microflow threw an unexpected exception: "  + e.getMessage(), e);
			throw e;
		}
	}

	private RequestContentType determineRequestContentType(HttpServletRequest request) {
		String ct = request.getHeader(RestServices.HEADER_CONTENTTYPE);
		if (ct == null) 
			return RequestContentType.OTHER;
		if (ct.contains("json"))
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
			if (ct.contains("json"))
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
		if (autoLogout && this.activeSession != null)
			Core.logout(this.activeSession);
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
	
	public String getPath() {
		return relpath;
	}
	
	private static final Map<String, RestServiceRequest> currentRequests = new ConcurrentHashMap<String, RestServiceRequest>(); 
	
	public <T> T withTransaction(final Function<T> worker) throws Exception {
		IContext c = getContext();
		Preconditions.checkNotNull(c, "RestServiceRequest has no context");
		
		if (c.isInTransaction())
			throw new IllegalStateException("Already in transaction");
		
		c.startTransaction();
		
		String transactionId = c.getTransactionId().toString();
		currentRequests.put(transactionId, this);
		
		boolean hasException = true;
		try {
			T res = Utils.withSessionCache(c, worker);
			hasException = false;
			return res;
		}
		finally {
			currentRequests.remove(transactionId);
			
			if (hasException)
				c.rollbackTransAction();
			else
				c.endTransaction();
		}
	}

	public static RestServiceRequest getCurrentRequest(IContext context) {
		return currentRequests.get(context.getTransactionId().toString());
	}

	public static String getRequestHeader(IContext context, String headerName) {
		RestServiceRequest current = getCurrentRequest(context);
		if (current == null)
			throw new IllegalStateException("Not handling a request currently");

		return current.request.getHeader(headerName);
	}

	public static List<Cookie> getRequestCookies(IContext context) {
		RestServiceRequest current = getCurrentRequest(context);
		if (current == null)
			throw new IllegalStateException("Not handling a request currently");

		List<Cookie> cookies = new ArrayList<Cookie>();
		for (javax.servlet.http.Cookie c : current.request.getCookies()) {
			Cookie r = new Cookie(context);
			cookies.add(r);
			
			r.setDomain(c.getDomain());
			r.setName(c.getName());
			r.setValue(c.getValue());
			r.setPath(c.getPath());
			r.setMaxAgeSeconds(c.getMaxAge());
		}
		
		return cookies;
	}
	
	public static void setResponseHeader(IContext context, String headerName, String value) {
		RestServiceRequest current = getCurrentRequest(context);
		if (current == null)
			throw new IllegalStateException("Not handling a request currently");

		current.response.setHeader(headerName, value);
	}

	public static void setResponseCookie(IContext context, Cookie cookie) {
		RestServiceRequest current = getCurrentRequest(context);
		if (current == null)
			throw new IllegalStateException("Not handling a request currently");
		if (cookie == null || cookie.getName().isEmpty())
			throw new IllegalArgumentException("Not a valid cookie");
		
		current.mxresponse.addCookie(cookie.getName(), cookie.getValue(), cookie.getPath(), cookie.getDomain() == null ? "" : cookie.getDomain(), cookie.getMaxAgeSeconds(), cookie.getHttpOnly());
	}

	public static void setResponseStatus(IContext context, int status) {
		RestServiceRequest current = getCurrentRequest(context);
		if (current == null)
			throw new IllegalStateException("Not handling a request currently");
		if (status < 200 || status >= 600)
			throw new IllegalArgumentException("Response status should be between 200 and 599");
		
		current.setStatus(status);
	}
}
