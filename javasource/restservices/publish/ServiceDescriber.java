package restservices.publish;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import restservices.RestServices;
import restservices.proxies.ServiceDefinition;
import restservices.publish.RestServiceRequest.ContentType;

public class ServiceDescriber {

	
	private static final String SINCEPARAM_HELPTEXT = "Number, defaulting to zero. Each change on the server side is assigned an unique, incremental number. Clients should keep track of the highest numbered change they already processed, to optimize the sync process";

	public static void serveServiceOverview(RestServiceRequest rsr) {
		rsr.startDoc();
		if (rsr.getContentType() == ContentType.HTML) 
			rsr.write("<h1>RestServices</h1>");

		rsr.datawriter.object()
			.key("RestServices").value(RestServices.VERSION)
			.key("services").array();
		
		for (String service : RestServices.getServiceNames())
			rsr.datawriter.value(RestServices.getServiceUrl(service) + "?" + RestServices.PARAM_ABOUT);
		
		rsr.datawriter.endArray().endObject();
		
		rsr.endDoc();
	}

	private RestServiceRequest rsr;
	private ServiceDefinition def;
	
/*	//TODO: replace with something recursive
	public Map<String, String> getPublishedMembers() {
		Map<String, String> res = new HashMap<String, String>();
 		for(IMetaPrimitive prim : this.getPublishMetaEntity().getMetaPrimitives())
 
			res.put(prim.getName(), prim.getType().toString());
		for(IMetaAssociation assoc : this.getPublishMetaEntity().getMetaAssociationsParent()) {
			PublishedService service = RestServices.getServiceForEntity(assoc.getChild().getName());
			if (service == null)
				continue;
			String name = Utils.getShortMemberName(assoc.getName());
			String type = assoc.getType() == AssociationType.REFERENCESET ? "[" + service.getServiceUrl() + "]" : service.getServiceUrl();
			res.put(name,  type);
		}
		return res;
	}
*/
	public ServiceDescriber(RestServiceRequest rsr, ServiceDefinition def) {
		this.rsr = rsr;
		this.def = def;
	}
	
	public void serveServiceDescription() {
		rsr.startDoc();
		if (rsr.getContentType() == ContentType.HTML) 
			rsr.write("<h1>Service: " + def.getName() + "</h1><a href='/rest/'>Back to the overview</a>");

		
		rsr.datawriter.object()
			.key("name").value(def.getName())
			.key("description").value(def.getDescription())
			.key("baseurl").value(RestServices.getServiceUrl(def.getName()))
			.key("worldreadable").value("*".equals(def.getAccessRole()))
			.key("requiresETags").value(def.getUseStrictVersioning())
			.key("endpoints").object();
			
		startEndpoint("GET", "?" + RestServices.PARAM_ABOUT, "This page");
		addContentType();
		endEndpoint();

		if (def.getEnableListing()) {
				startEndpoint("GET", "/", "List the URL of all objects published by this service");
				addEndpointParam(RestServices.PARAM_DATA, "'true' or 'false'. Whether to list the URL (false) of the objects, or output the objects themselves (true). Defaults to 'true'");
				addContentType();
				endEndpoint();
			}
			if (def.getEnableGet()) {
				startEndpoint("GET", "/<" + def.getSourceKeyAttribute() + ">", "Returns the object specified by the URL, which is retrieved from the database by using the given key.");
				addEndpointParam(RestServices.IFNONEMATCH_HEADER + " (header)", "If the current version of the object matches this optional header, status 304 NOT MODIFIED will be returned instead of returning the whole objects. This header can be used for caching / performance optimization");
				addContentType();
				endEndpoint();
			}
			if (def.getEnableChangeTracking()) {
				startEndpoint("GET", "/changes/list", "Returns a list of incremental changes that allows the client to synchronize with recent changes on the server");
				addEndpointParam(RestServices.PARAM_SINCE, SINCEPARAM_HELPTEXT);
				addContentType();
				endEndpoint();
				
				startEndpoint("GET", "/changes/feed", "Returns a list of incremental changes that allows the client to synchronize with recent changes on the server. The feed, in contrast to list, keeps the connection open to be able to push any new change directly to the client, without the client needing to actively request for new changes. (a.k.a. push over longpolling HTTP)"); 
				addEndpointParam(RestServices.PARAM_SINCE, SINCEPARAM_HELPTEXT);
				addEndpointParam(RestServices.PARAM_TIMEOUT, "Maximum time the current feed connecion is kept open. Defaults to 50 seconds to avoid firewall issues. Once this timeout exceeds, the connection is closed and the client should automatically reconnect. Use zero to never expire. Use a negative number to indicate that the connection should expire whenever the timeout is exceed, *or* when a new change arrives. This is useful for clients that cannot read partial responses");
				addContentType();
				endEndpoint();
			}
			if (def.getEnableCreate()) {
				startEndpoint("POST", "/", "Stores an object as new entry in the collection served by this service. Returns the (generated) key of the new object");
				addBodyParam();
				addEtagParam();
				endEndpoint();
				
				startEndpoint("PUT", "/<" + def.getSourceKeyAttribute() + ">", "Stores an object as new entry in the collection served by this service, under te given key. This key shouldn't exist yet.");
				addBodyParam();
				addEtagParam();
				endEndpoint();
			}
			if (def.getEnableUpdate()) {
				startEndpoint("PUT", "/<" + def.getSourceKeyAttribute() + ">", "Updates the object with the given key. If the key does not exist yet, " +  (def.getEnableCreate() ? "the object will be created" : " the request will fail"));
				addBodyParam();
				addEtagParam();
				endEndpoint();
			}
			if (def.getEnableDelete()) {
				startEndpoint("DELETE", "/<" + def.getSourceKeyAttribute() + ">", "Deletes the object identified by the key");
				addBodyParam();
				addEtagParam();
				endEndpoint();
			}
		
		rsr.datawriter.endObject().endObject();
		rsr.endDoc();
	}

	private void addEndpointParam(String param, String description) {
		rsr.datawriter.object().key("name").value(param).key("description").value(description).endObject();
	}

	private void startEndpoint(String method, String url, String description) {
		rsr.datawriter.object()
			.key("path").value(method + " " + url)
			.key("description").value(description)
			.key("params").array();
	}


}
