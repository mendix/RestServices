package restservices;

import java.util.List;

import org.codehaus.jackson.annotate.JsonProperty;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.connectionbus.requests.IRetrievalSchema;
import com.mendix.systemwideinterfaces.connectionbus.requests.ISortExpression.SortDirection;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

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

	public void consistencyCheck() {
		// source exists and persistent
		// keyattr exists
		// constraint is valid
		// name is a valid identifier
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
		return Core.getConfiguration().getApplicationRootUrl() + this.servicename;
	}
}

