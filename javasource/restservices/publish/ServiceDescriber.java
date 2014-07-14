package restservices.publish;

import org.apache.commons.httpclient.HttpStatus;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.proxies.DataServiceDefinition;
import restservices.publish.RestServiceRequest.ResponseType;
import restservices.util.JSONSchemaBuilder;
import com.mendix.core.Core;
import communitycommons.StringUtils;

public class ServiceDescriber {

	
	private static final String SINCEPARAM_HELPTEXT = "Number, defaulting to zero. Each change on the server side is assigned an unique, incremental number. Clients should keep track of the highest numbered change they already processed, to optimize the sync process. For feeds, since '-1' can be used to indicate that past revisions can be skipped and the feed should only push new changes. ";

	public static void serveServiceOverview(RestServiceRequest rsr) {
		rsr.startDoc();
		if (rsr.getResponseContentType() == ResponseType.HTML) 
			rsr.write("<h1>RestServices</h1>");

		rsr.datawriter.object()
			.key("RestServices").value(RestServices.VERSION)
			.key("services").array();
		
		for (String service : RestServiceHandler.getServiceBaseUrls())
			rsr.datawriter.value(RestServices.getAbsoluteUrl(service) + "?" + RestServices.PARAM_ABOUT);
		
		rsr.datawriter.endArray().endObject();
		
		rsr.endDoc();
	}

	private RestServiceRequest rsr;
	private DataServiceDefinition def;
	private boolean isHTML;
	
	public ServiceDescriber(RestServiceRequest rsr, DataServiceDefinition def) {
		this.rsr = rsr;
		this.def = def;
		this.isHTML = rsr.getResponseContentType() == ResponseType.HTML;
	}
	
	public void serveServiceDescription() {
		rsr.startDoc();
		if (isHTML) { 
			rsr.write("<h1>Service: " + def.getName() + "</h1><a href='/" + RestServices.PATH_REST + "'>");
		}
		
		rsr.datawriter.object()
			.key("name").value(def.getName())
			.key("description").value(def.getDescription())
			.key("baseurl").value(RestServices.getAbsoluteUrl(def.getName()))
			.key("worldreadable").value("*".equals(def.getAccessRole()))
			.key("requiresETags").value(def.getUseStrictVersioning());
			
		if (isHTML)
			rsr.datawriter.endObject();
		else
			rsr.datawriter.key("endpoints").array();
			
		startEndpoint("GET", "?" + RestServices.PARAM_ABOUT, "This page");
		addContentType();
		endEndpoint();

		if (def.getEnableListing()) {
				startEndpoint("GET", "?" + RestServices.PARAM_COUNT, "Returns the amount of objects available in this service");
				addContentType();
				endEndpoint();
				
				startEndpoint("GET", "", "List the URL of all objects published by this service");
				addEndpointParam(RestServices.PARAM_DATA, "'true' or 'false'. Whether to list the URLs (false) of each of the objects, or output the objects themselves (true). Defaults to 'false'");
				addEndpointParam(RestServices.PARAM_OFFSET, "positive number, optional argument");
				addEndpointParam(RestServices.PARAM_LIMIT, "positive number, optional argument");
				addContentType();
				endEndpoint();
			}
			if (def.getEnableGet()) {
				startEndpoint("GET", "<" + def.getSourceKeyAttribute() + ">", "Returns the object specified by the URL, which is retrieved from the database by using the given key.");
				addEndpointParam(RestServices.HEADER_IFNONEMATCH + " (header)", "If the current version of the object matches the ETag provided by this optional header, status 304 NOT MODIFIED will be returned instead of returning the whole objects. This header can be used for caching / performance optimization");
				addContentType();
				
				JSONObject schema = JSONSchemaBuilder.build(Core.getReturnType(def.getOnPublishMicroflow()));
				addEndpointParam("(request body)", schema);
				
				endEndpoint();
			}
			if (def.getEnableChangeLog()) {
				startEndpoint("GET", "changes/list", "Returns a list of incremental changes that allows the client to synchronize with recent changes on the server");
				addEndpointParam(RestServices.PARAM_SINCE, SINCEPARAM_HELPTEXT);
				addContentType();
				endEndpoint();
				
				startEndpoint("GET", "changes/feed", "Returns a list of incremental changes that allows the client to synchronize with recent changes on the server. The feed, in contrast to list, keeps the connection open to be able to push any new change directly to the client, without the client needing to actively request for new changes. (a.k.a. push over longpolling HTTP)"); 
				addEndpointParam(RestServices.PARAM_SINCE, SINCEPARAM_HELPTEXT);
				addEndpointParam(RestServices.PARAM_TIMEOUT, "Maximum time the current feed connecion is kept open. Defaults to 50 seconds to avoid firewall issues. Once this timeout exceeds, the connection is closed and the client should automatically reconnect. Use zero to never expire. Use a negative number to indicate that the connection should expire whenever the timeout is exceed, *or* when a new change arrives. This is useful for clients that cannot read partial responses");
				addContentType();
				endEndpoint();
			}
			if (def.getEnableCreate()) {
				startEndpoint("POST", "", "Stores an object as new entry in the collection served by this service. Returns the (generated) key of the new object");
				addBodyParam();
				addEtagParam();
				endEndpoint();
				
				startEndpoint("PUT", "<" + def.getSourceKeyAttribute() + ">", "Stores an object as new entry in the collection served by this service, under te given key. This key shouldn't exist yet.");
				addBodyParam();
				addEtagParam();
				endEndpoint();
			}
			if (def.getEnableUpdate()) {
				startEndpoint("PUT", "<" + def.getSourceKeyAttribute() + ">", "Updates the object with the given key. If the key does not exist yet, " +  (def.getEnableCreate() ? "the object will be created" : " the request will fail"));
				addBodyParam();
				addEtagParam();
				endEndpoint();
			}
			if (def.getEnableDelete()) {
				startEndpoint("DELETE", "<" + def.getSourceKeyAttribute() + ">", "Deletes the object identified by the key");
				addBodyParam();
				addEtagParam();
				endEndpoint();
			}
		
		if (!isHTML)
			rsr.datawriter.endArray().endObject();
		
		rsr.endDoc();
	}

	private void addEtagParam() {
		addEndpointParam(RestServices.HEADER_IFNONEMATCH + "(header)", "Both GET requests that returns an individual object and the changes api return ETag's (by using the " + RestServices.HEADER_ETAG + " header). " +
				"If a " + RestServices.HEADER_IFNONEMATCH + " header is provided, the server might respond with a 304 NOT MODIFIED response, to indicate that the object was not changed since the previous time it was requested. In this case no data is returned." +
				"If the 'requiresETags' setting is enabled, the " + RestServices.HEADER_IFNONEMATCH + " is required for request that alter data (PUT or DELETE). If the ETag is invalid in such a case, another party already updated the object and this update is reject since it was based on a stale copy. The server will respond with http status " + HttpStatus.SC_CONFLICT + " CONFLICT");
	}

	private void addBodyParam() {
		addEndpointParam("Request payload (body)", "Should be valid JSON data");
	}

	private void addContentType() {
		addEndpointParam("contenttype (param) or " + RestServices.HEADER_ACCEPT + " (header)", "Either 'json', 'html' or 'xml'. If the header is used, one of those three values is extracted from the headers. This parameter is used to determine the output type. This results in an HTML represention in browsers (unless overriden using the param) and Json or XML data for non-browser clients.");
	}

	private void addEndpointParam(String param, Object description) {
		if (isHTML) {
			rsr.write("<tr><td>" + param + "</td><td>"); 
			rsr.datawriter.value(description);
			rsr.write("</td></tr>");
		}
		else
			rsr.datawriter.object().key("name").value(param).key("description").value(description).endObject();
	}

	private void startEndpoint(String method, String path, String description) {
		String url = RestServices.getAbsoluteUrl(def.getName()) + path;
		if (isHTML) {
			String link = "<small>" + RestServices.getBaseUrl() + "</small>" + StringUtils.HTMLEncode(url.substring(RestServices.getBaseUrl().length()));
			if ("GET".equals(method))
				link = "<a href='" + url + "'>" + link + "</a>";
			
			rsr.write("<h2>" + method + "&raquo;&nbsp;&nbsp;&nbsp;" + link + "</h2>")
				.write("<p>" + description + "</p>")
				.write("<table><tr><th>Parameter</th><th>Description</th></tr>");
		}
		else
			rsr.datawriter.object()
				.key("path").value(method + " " + url)
				.key("description").value(description)
				.key("params").array();
	}
	
	private void endEndpoint() {
		if (isHTML)
			rsr.write("</table>");
		else
			rsr.datawriter.endArray().endObject();
	}
}
