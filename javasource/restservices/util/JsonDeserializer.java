package restservices.util;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.consume.RestConsumer;
import restservices.proxies.HttpMethod;
import restservices.proxies.Primitive;
import restservices.proxies.RestPrimitiveType;

import com.mendix.core.Core;
import com.mendix.core.objectmanagement.member.MendixObjectReference;
import com.mendix.core.objectmanagement.member.MendixObjectReferenceSet;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive.PrimitiveType;

public class JsonDeserializer {

	public static IMendixIdentifier readJsonDataIntoMendixObject(IContext context,
			Object jsonValue, String targetType, boolean autoResolveReferences) throws Exception {
		IMendixObject target = Core.instantiate(context, targetType);
		readJsonDataIntoMendixObject(context, jsonValue, target, autoResolveReferences);
		return target.getId();
	}
	
	public static void readJsonDataIntoMendixObject(IContext context,
			Object jsonValue, IMendixObject target, boolean autoResolveReferences) throws Exception {
		String targetType = target.getType();
		
		//primitive
		if (Core.isSubClassOf(Primitive.entityName, targetType)) {
			Primitive prim = Primitive.initialize(context, target);
			prim.setStringValue(String.valueOf(jsonValue));
			if (jsonValue == null || jsonValue == JSONObject.NULL)
				prim.setPrimitiveType(RestPrimitiveType._NULL);
			else if (jsonValue instanceof String) 
				prim.setPrimitiveType(RestPrimitiveType.String);
			else if (jsonValue instanceof Boolean) {
				prim.setBooleanValue((Boolean) jsonValue);
				prim.setPrimitiveType(RestPrimitiveType._Boolean);
			}
			else if (jsonValue instanceof Long || jsonValue instanceof Double || jsonValue instanceof Integer || jsonValue instanceof Float) {
				prim.setPrimitiveType(RestPrimitiveType.Number);
				prim.setNumberValue(Double.parseDouble(jsonValue.toString()));
			}
			else
				throw new RuntimeException("Unable to convert value of type '" + jsonValue.getClass().getName()+ "' to rest primitive: " + jsonValue.toString());
		}
		
		//string; autoresolve
		else if (jsonValue instanceof String) {
			if (!autoResolveReferences)
				throw new RuntimeException("Unable to read url '" + jsonValue + "' into '" + targetType + "'; since references will not be resolved automatically for incoming data");
			RestConsumer.request(context, HttpMethod.GET, (String) jsonValue, null, target, false);
		}
		
		else if (jsonValue instanceof JSONObject) {
			readJsonObjectIntoMendixObject(context, (JSONObject) jsonValue, target, autoResolveReferences);
		}
		
		else
			throw new RuntimeException("Unable to parse '" + jsonValue.toString() + "' into '" + targetType + "'");
	}
	
	private static void readJsonObjectIntoMendixObject(IContext context, JSONObject object, IMendixObject target, boolean autoResolve) throws JSONException, Exception {
		Iterator<String> it = object.keys();

		while(it.hasNext()) {
			String attr = it.next();
			String targetattr =  null;
			
			if (target.hasMember(attr)) 
				targetattr = attr;
			else if (target.hasMember(target.getMetaObject().getModuleName() + "." + attr)) 
				targetattr = target.getMetaObject().getModuleName() + "." + attr;
			if (target.hasMember("_" + attr)) //To support attributes with names which are reserved names in Mendix 
				targetattr = "_" + attr;
			else if (target.hasMember(target.getMetaObject().getModuleName() + "._" + attr)) 
				targetattr = target.getMetaObject().getModuleName() + "._" + attr;
			
			if (targetattr == null) {
				if (RestServices.LOGUTIL.isDebugEnabled())
					RestServices.LOGUTIL.debug("Skipping attribute '" + attr + "', not found in targettype: '" + target.getType() + "'");
				continue;
			}
			
			IMendixObjectMember<?> member = target.getMember(context, targetattr);
			
			if (member.isVirtual())
				continue;
			
			//Reference
			else if (member instanceof MendixObjectReference) {
				String otherSideType = target.getMetaObject().getMetaAssociationParent(targetattr).getChild().getName();
				if (!object.isNull(attr)) 
					((MendixObjectReference)member).setValue(context, readJsonDataIntoMendixObject(context, object.get(attr), otherSideType, autoResolve));
			}
			
			//ReferenceSet
			else if (member instanceof MendixObjectReferenceSet){
				String otherSideType = target.getMetaObject().getMetaAssociationParent(targetattr).getChild().getName();
				JSONArray children = object.getJSONArray(attr);
				List<IMendixIdentifier> ids = new ArrayList<IMendixIdentifier>();
				
				for(int i = 0; i < children.length(); i++) {
					IMendixIdentifier child = readJsonDataIntoMendixObject(context, children.get(i), otherSideType, autoResolve);
					if (child != null)
						ids.add(child);
				}
				
				((MendixObjectReferenceSet)member).setValue(context, ids);
			}
			
			//Primitive member
			else if (target.hasMember(targetattr)){
				PrimitiveType attrtype = target.getMetaObject().getMetaPrimitive(targetattr).getType();
				if (attrtype != PrimitiveType.AutoNumber)
					target.setValue(context, targetattr, jsonAttributeToPrimitive(attrtype, object, attr));
			}
		}
		Core.commit(context, target);
	}

	private static Object jsonAttributeToPrimitive(PrimitiveType type,	JSONObject object, String attr) throws Exception {
		switch(type) {
		case Currency:
		case Float:
			return object.getDouble(attr);
		case Boolean:
			return object.getBoolean(attr);
		case DateTime: 
			if (object.isNull(attr))
				return null;
			return new Date(object.getLong(attr));
		case Enum:
		case HashString:
		case String:
			return object.getString(attr);
		case AutoNumber:
		case Integer:
		case Long:
			return object.getLong(attr);
		case Binary:
		default:
			throw new Exception("Unsupported attribute type '" + type + "' in attribute '" + attr + "'");
		}	
	}

}
