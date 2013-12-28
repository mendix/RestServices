package restservices.consume;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.proxies.Primitive;
import restservices.proxies.RestObject;
import restservices.proxies.RestPrimitiveType;
import restservices.proxies.RestReference;

import com.mendix.core.Core;
import com.mendix.core.objectmanagement.member.MendixObjectReference;
import com.mendix.core.objectmanagement.member.MendixObjectReferenceSet;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive.PrimitiveType;

public class JsonDeserializer {

	public static void readJsonObjectIntoMendixObject(IContext context, JSONObject object, IMendixObject target, ObjectCache cache) throws JSONException, Exception {
		Iterator<String> it = object.keys();
		while(it.hasNext()) {
			String attr = it.next();
			String targetattr =  null;
			//String assocName = target.getMetaObject().getModuleName() + "." + attr;
			
			if (target.hasMember(attr)) 
				targetattr = attr;
			else if (target.hasMember(target.getMetaObject().getModuleName() + "." + attr)) 
				targetattr = target.getMetaObject().getModuleName() + "." + attr;
			if (target.hasMember("_" + attr)) //To support attributes with names which are reserved names in Mendix 
				targetattr = "_" + attr;
			else if (target.hasMember(target.getMetaObject().getModuleName() + "._" + attr)) 
				targetattr = target.getMetaObject().getModuleName() + "._" + attr;
			
			if (targetattr == null) {
				if (RestServices.LOG.isDebugEnabled())
					RestServices.LOG.debug("Skipping attribute '" + attr + "', not found in targettype: '" + target.getType() + "'");
				continue;
			}
			
			IMendixObjectMember<?> member = target.getMember(context, targetattr);
			
			if (member.isVirtual())
				continue;
			
			//Reference
			else if (member instanceof MendixObjectReference) {
				String otherSideType = target.getMetaObject().getMetaAssociationParent(targetattr).getChild().getName();
				if (!object.isNull(attr)) 
					((MendixObjectReference)member).setValue(context, readJsonValueIntoMendixObject(context, object.get(attr), otherSideType, cache));
			}
			
			//ReferenceSet
			else if (member instanceof MendixObjectReferenceSet){
				String otherSideType = target.getMetaObject().getMetaAssociationParent(targetattr).getChild().getName();
				JSONArray children = object.getJSONArray(attr);
				List<IMendixIdentifier> ids = new ArrayList<IMendixIdentifier>();
				
				for(int i = 0; i < children.length(); i++) {
					IMendixIdentifier child = readJsonValueIntoMendixObject(context, children.get(i), otherSideType, cache);
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

	private static IMendixIdentifier readJsonValueIntoMendixObject(IContext context,
			Object jsonValue, String targetType, ObjectCache cache) throws JSONException, Exception {
		//TODO: use cache
		IMendixObject res = Core.instantiate(context, targetType);
		
		//Primitive
		if (Core.isSubClassOf(Primitive.entityName, targetType)) {
			Primitive prim = Primitive.initialize(context, res);
			if (jsonValue instanceof String) {
				prim.setStringValue((String) jsonValue);
				prim.setPrimitiveType(RestPrimitiveType.String);
			}
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
		
		//Reference
		else if (Core.isSubClassOf(RestReference.entityName, targetType)) {
			if (!(jsonValue instanceof String))
				throw new RuntimeException("Expected json string value to create reference to '" + targetType + "'");
			res.setValue(context, RestServices.URL_ATTR, jsonValue);
		}
		
		else if (Core.isSubClassOf(RestObject.entityName, targetType)) {
			if (!(jsonValue instanceof String))
				throw new RuntimeException("Expected json string value to create reference to '" + targetType + "'");
			res = cache.getObject(context, (String) jsonValue, targetType);
		}
		else {
			if (!(jsonValue instanceof JSONObject))
				throw new RuntimeException("Expected json object value to create reference to '" + targetType + "'");
			readJsonObjectIntoMendixObject(context, (JSONObject) jsonValue, res, cache);
		}
		Core.commit(context, res);
		return res.getId();
	
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
			//TODO: use RestException everywhere
			throw new Exception("Unsupported attribute type '" + type + "' in attribute '" + attr + "'");
		}	
	}

}
