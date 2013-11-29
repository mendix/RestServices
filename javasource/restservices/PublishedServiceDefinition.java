package restservices;

import org.codehaus.jackson.annotate.JsonProperty;

public class PublishedServiceDefinition {

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
}
