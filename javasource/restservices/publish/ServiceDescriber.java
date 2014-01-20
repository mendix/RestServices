package restservices.publish;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import restservices.RestServices;
import restservices.proxies.ServiceDefinition;
import restservices.publish.RestServiceRequest.ContentType;

public class ServiceDescriber {

	
	public static void serveServiceOverview(RestServiceRequest rsr) {
		if (rsr.getContentType() == ContentType.XML) {
			rsr.startXMLDoc();
			rsr.write("<RestServices>");
		}
		else if (rsr.getContentType() == ContentType.HTML) {
			rsr.startHTMLDoc();
			rsr.write("<h1>RestServices</h1>");
		}

		rsr.datawriter.object()
			.key("RestServices").value(RestServices.VERSION)
			.key("services").array();
		
		//TODO:
		//for (String service : RestServices.getServiceNames())
		//	ServiceDescriber.serveServiceDescription(rsr);
		
		rsr.datawriter.endArray().endObject();
		
		if (rsr.getContentType() == ContentType.XML) 
			rsr.write("</RestServices>");
		else if (rsr.getContentType() == ContentType.HTML) 
			rsr.endHTMLDoc();

		rsr.close();
	}
	
	//TODO: replace with something recursive
	public Map<String, String> getPublishedMembers() {
		Map<String, String> res = new HashMap<String, String>();
/* TODO: determine published meta entity
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
*/
		return res;
	}
	
	public static void serveServiceDescription(RestServiceRequest rsr, ServiceDefinition def) {
		rsr.datawriter.object()
			.key("name").value(def.getName())
			.key("url").value(RestServices.getServiceUrl(def.getName()))
			//TODO: export description
			.key("attributes").object();
		
		//for(Entry<String, String> e : getPublishedMembers().entrySet()) 
		//	rsr.datawriter.key(e.getKey()).value(e.getValue());
		
		rsr.datawriter.endObject().endObject();
	}


}
