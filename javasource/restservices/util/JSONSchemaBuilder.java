package restservices.util;

import java.util.HashMap;

import org.json.JSONObject;
import org.json.JSONArray;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation.AssociationType;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive.PrimitiveType;

public class JSONSchemaBuilder {

	private IMetaObject baseObject;
	private JSONObject result;
	private int typeCounter = 1;
	private HashMap<String, String> typeMap;
	private JSONObject definitions;
	
	private JSONSchemaBuilder(IMetaObject meta) {
		this.baseObject = meta;
	}

	private JSONObject build() {
		this.typeMap = new HashMap<String, String>();
		
		this.result = new JSONObject();
		this.result.put("$schema", "http://json-schema.org/draft-04/schema#");
		
		this.definitions = new JSONObject();
		this.result.put("definitions", definitions);
		
		buildTypeDefinition(baseObject);
		
		this.result.put("$ref", "#/definitions/type1");
		return result;
	}
	
	private void buildTypeDefinition(IMetaObject meta) {
		if (typeMap.containsKey(meta.getName()))
			return; 
		
		typeMap.put(meta.getName(),  "type" + typeCounter);
		JSONObject def = new JSONObject();
		definitions.put("type" + typeCounter, def);
		typeCounter += 1;
		
		def.put("type", "object");
		JSONObject properties = new JSONObject();
		def.put("properties", properties);
		
		//TODO: sort primitives
		for(IMetaPrimitive prim : meta.getMetaPrimitives()) {
			JSONObject type = primitiveToJSONType(prim.getType());
			if (type != null)
				properties.put(prim.getName(), type);
		}
		
		//TODO: sort assocs
		for(IMetaAssociation assoc : meta.getMetaAssociationsParent()) {
			JSONObject type = associationToJSONType(assoc);
			if (type != null)
				properties.put(assoc.getName().split("\\.")[1], type);
		}
	}

	private JSONObject associationToJSONType(IMetaAssociation assoc) {
		IMetaObject child = assoc.getChild();
		
		JSONObject type = null;
		
		//some kind of foreign key
		if (child.isPersistable()) {
			//TODO: only if there is an service;
			type = new JSONObject()
				.put("type", "string")
				.put("title", String.format("Reference to a(n) '%s'", child.getName()));
		}
		
		//persistent object, describe this object in the service as well
		else {
			buildTypeDefinition(child); //make sure the type is available in the schema
			type = new JSONObject().put("$ref", "#/definitions/" + typeMap.get(child.getName()));
		}

		//assoc should be included?
		if (type == null)
			return null;
		
		//make sure null refs are supported
		type = orNull(type);
		
		//make sure referencesets require arrays
		if (assoc.getType() == AssociationType.REFERENCESET)
			type = new JSONObject().put("type", "array").put("items", type);
		
		return type;
	}

	private static JSONObject primitiveToJSONType(PrimitiveType type) {
		switch(type) {
		case AutoNumber:
		case DateTime:
		case Integer:
		case Long:  
			return new JSONObject().put("type", "number").put("multipleOf", "1.0");
		case Binary:
			return null;
		case Boolean:
			return new JSONObject().put("type", "boolean");
		case Currency:
		case Float:
			return new JSONObject().put("type", "number");
		case Enum:
			return orNull(new JSONObject().put("type", "string")); //TODO: use enum from the meta model!
		case HashString:
		case String:
			return orNull(new JSONObject().put("type", "string"));
		default:
			throw new IllegalStateException("Unspported primitive type:  " + type);
		}
	}
	
	private static JSONObject orNull(JSONObject type) {
		return new JSONObject().put("oneOf", new JSONArray()
			.put(new JSONObject().put("type", JSONObject.NULL))
			.put(type)
		);
	}
		
	public static JSONObject build(IMetaObject meta) {
		return new JSONSchemaBuilder(meta).build();
	}
	
	public static JSONObject build(IDataType type) {
		if (type.isMendixObject())
			return build(Core.getMetaObject(type.getObjectType()));
		PrimitiveType primType = PrimitiveType.valueOf(type.getType().toString()); //MWE: WTF, there are two different types of data types?!
		return primitiveToJSONType(primType);
	}
}
