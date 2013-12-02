package restservices;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.codec.digest.DigestUtils;
import org.codehaus.jackson.annotate.JsonProperty;
import org.json.JSONObject;
import org.json.JSONWriter;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multiset.Entry;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.connectionbus.requests.IRetrievalSchema;
import com.mendix.systemwideinterfaces.connectionbus.requests.ISortExpression.SortDirection;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;

import communitycommons.XPath;

public class PublishedService {

	private static final long BATCHSIZE = 1000;

	@JsonProperty
	private String servicename;
	
	@JsonProperty
	private String sourceentity;
	
	@JsonProperty
	private String constraint;
	
	@JsonProperty
	private String keyattribute;
	
	@JsonProperty
	private String publishentity;
	
	@JsonProperty
	private String publishmicroflow;

	private IMetaObject metaEntity;

	public void consistencyCheck() {
		// source exists and persistent
		// keyattr exists
		// constraint is valid
		// name is a valid identifier
		// reserved attributes: _key, _etag
		// no password type attributes
	}

	public String getName() {
		return servicename;
	}

	public String getSourceEntity() {
		return sourceentity;
	}

	public void serveListing(RestServiceRequest rsr) throws CoreException {
		IRetrievalSchema schema = Core.createRetrievalSchema();
		schema.addSortExpression(this.keyattribute, SortDirection.ASC);
		schema.addMetaPrimitiveName(this.keyattribute);
		schema.setAmount(BATCHSIZE);

		switch(rsr.getContentType()) {
		case HTML:
			rsr.startHTMLDoc();
			break;
		case XML:
			rsr.startXMLDoc();
			rsr.write("<items>");
			break;
		case JSON:
			rsr.jsonwriter.array();
			break;
		}
		
		long offset = 0;
		List<IMendixObject> result;
		do {
			schema.setOffset(offset);
			result = Core.retrieveXPathSchema(rsr.getContext(), "//" + this.sourceentity + this.constraint, schema, false);
		
			for(IMendixObject item : result) {
				String key = item.getMember(rsr.getContext(), keyattribute).parseValueToString(rsr.getContext());
				if (!RestServices.isValidKey(key))
					continue;

				String url = this.getBaseUrl() + "/" + key; //TODO: url param encode key?
				switch(rsr.getContentType()) {
				case HTML:
					rsr.write("\n<a href='").write(url).write("'>").write(key).write("</a><br />");
					break;
				case XML:
					rsr.write("\n<item>").write(url).write("</item>");
					break;
				case JSON:
					rsr.jsonwriter.value(url);
					break;
				}
			}
			
			offset += BATCHSIZE;
		}
		while(!result.isEmpty());
		
		switch(rsr.getContentType()) {
		case HTML:
			rsr.endHTMLDoc();
			break;
		case XML:
			rsr.write("</items>");
			break;
		case JSON:
			rsr.jsonwriter.endArray();
			break;
		}
		
		rsr.close();
	}

	private String getBaseUrl() {
		return Core.getConfiguration().getApplicationRootUrl() + "rest/" + this.servicename;
	}

	public void serveGet(RestServiceRequest rsr, String key) throws Exception {
		String xpath = XPath.create(rsr.getContext(), this.sourceentity).eq(this.keyattribute, key).getXPath() + this.constraint;
		
		List<IMendixObject> results = Core.retrieveXPathQuery(rsr.getContext(), xpath, 1, 0, ImmutableMap.of("id", "ASC"));
		if (results.size() == 0) {
			rsr.setStatus(IMxRuntimeResponse.NOT_FOUND);
			return;
		}
		
		IMendixObject view = convertSourceToView(rsr, results.get(0));
		JSONObject result = convertViewToJson(rsr, view);
		
		String jsonString = result.toString(4);
		String eTag = DigestUtils.md5Hex(jsonString.getBytes(RestServices.UTF8));
		
		if (eTag.equals(rsr.request.getHeader(RestServices.IFNONEMATCH_HEADER))) {
			rsr.setStatus(IMxRuntimeResponse.NOT_MODIFIED);
			rsr.close();
			return;
		}
		
		rsr.response.setHeader(RestServices.ETAG_HEADER, eTag);
		
		switch(rsr.getContentType()) {
		case JSON:
			rsr.write(jsonString);
			break;
		case HTML:
			rsr.startHTMLDoc();
			rsr.write("<h1>").write(key).write("</h1>");
			writeJSONDocAsHTML(rsr, result);
			rsr.endHTMLDoc();
			break;
		case XML:
			rsr.startXMLDoc(); //TODO: doesnt JSON.org provide a toXML?
			writeJSONDocAsXML(rsr, result);
			break;
		}
		
		rsr.close();
	}

	private void writeJSONDocAsXML(RestServiceRequest rsr, JSONObject result) {
		rsr.write("<" + this.servicename + ">");
		Iterator<String> it = result.keys();
		while(it.hasNext()) {
			String attr = it.next();
			rsr.write("<").write(attr).write(">");
			if (result.isJSONArray(attr)) {
				//TODO:
			}
			else if (result.get(attr) != null)
				rsr.write(String.valueOf(result.get(attr)));
				
			rsr.write("</").write(attr).write(">");
		}
		rsr.write("</" + this.servicename + ">");
	}

	private void writeJSONDocAsHTML(RestServiceRequest rsr, JSONObject result) {
		rsr.write("<table>");
		Iterator<String> it = result.keys();
		while(it.hasNext()) {
			String attr = it.next();
			if (result.isJSONArray(attr)) {
				//TODO:
			}
			else
				rsr.write(String.format(
					"\n<tr><td>%s</td><td>%s</td></tr>", attr, rsr.autoGenerateLink(String.valueOf(result.get(attr)))));
		}
		rsr.write("</table>");
	}

	private JSONObject convertViewToJson(RestServiceRequest rsr, IMendixObject view) throws Exception {
		JSONObject res = new JSONObject();
		
		Map<String, ? extends IMendixObjectMember<?>> members = view.getMembers(rsr.getContext());
		for(java.util.Map.Entry<String, ? extends IMendixObjectMember<?>> e : members.entrySet())
			RestServices.serializeMember(rsr.getContext(), res, e.getValue(), view.getMetaObject());
		
		return res;
	}

	private IMetaObject getMetaEntity() {
		if (this.metaEntity == null)
			this.metaEntity = Core.getMetaObject(this.sourceentity);
		return this.metaEntity;
	}

	private IMendixObject convertSourceToView(RestServiceRequest rsr, IMendixObject source) throws CoreException {
		return (IMendixObject) Core.execute(rsr.getContext(), this.publishmicroflow, source);
	}
}

