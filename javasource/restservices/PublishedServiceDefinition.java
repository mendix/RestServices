package restservices;

import java.util.List;

import org.codehaus.jackson.annotate.JsonProperty;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.connectionbus.requests.IRetrievalSchema;
import com.mendix.systemwideinterfaces.connectionbus.requests.ISortExpression.SortDirection;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

public class PublishedServiceDefinition {

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

		rsr.write("[");
		
		long offset = 0;
		boolean first = true;
		List<IMendixObject> result;
		do {
			schema.setOffset(offset);
			result = Core.retrieveXPathSchema(rsr.getContext(), "//" + this.sourceentity + this.constraint, schema, false);
		
			for(IMendixObject item : result) {
				String key = item.getMember(rsr.getContext(), keyattribute).parseValueToString(rsr.getContext());
				if (!RestServices.isValidKey(key))
					continue;
				
				if (first)
					first = false;
				else
					rsr.write(", ");
				rsr.write(this.getBaseUrl() + "/" + key);
			}
			
			offset += BATCHSIZE;
		}
		while(!result.isEmpty());
		
		rsr.write("]");
	}

	private String getBaseUrl() {
		return Core.getConfiguration().getApplicationRootUrl() + this.servicename;
	}
}

