package restservices.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.consume.RestConsumer;
import restservices.proxies.HttpMethod;
import restservices.proxies.Primitive;
import restservices.proxies.RestPrimitiveType;
import restservices.proxies.BooleanValue;

import com.mendix.core.Core;
import com.mendix.core.objectmanagement.member.MendixObjectReference;
import com.mendix.core.objectmanagement.member.MendixObjectReferenceSet;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;
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
		if (!Utils.hasDataAccess(target.getMetaObject(), context))
			throw new IllegalStateException("During JSON deserialization: Object of type '" + target.getType() + "' cannot be altered by users with role(s) " + context.getSession().getUserRolesNames() + ". Please check the security rules");
	
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
				prim.setNumberValue(new BigDecimal(jsonValue.toString()));
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

		Map<String, String> attributeNameMap = buildAttributeNameMap(target.getMetaObject());
		
		while(it.hasNext()) {
			String attr = it.next();
			String targetattr =  attributeNameMap.get(attr.toLowerCase().replaceAll("[^a-zA-Z0-9_]","_"));
			
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
					if (child != null) {
						/*
						 * The core.createMendixIdentifier should be unnecessary, however, there is a bug there, see
						 * support ticket 102188 
						 */
						ids.add(Core.createMendixIdentifier(child.toLong()));
					}
				}
				
				((MendixObjectReferenceSet)member).setValue(context, ids);
			}
			
			//Primitive member
			else if (target.hasMember(targetattr)){
				IMetaPrimitive primitive = target.getMetaObject().getMetaPrimitive(targetattr);
				if (primitive.getType() != PrimitiveType.AutoNumber)
					target.setValue(context, targetattr, jsonAttributeToPrimitive(primitive, object, attr));
			}
		}
		Core.commit(context, target);
	}

	private static final Map<String, Map<String,String>> metaAttributeMaps = new HashMap<String, Map<String, String>>();
	
	private static Map<String, String> buildAttributeNameMap(IMetaObject metaObject) {
		if (metaAttributeMaps.containsKey(metaObject.getName()))
			return metaAttributeMaps.get(metaObject.getName());
		
		Map<String, String> attrMap = new HashMap<String,String>();
		
		for(IMetaAssociation assoc : metaObject.getMetaAssociationsParent()) {
			String name = assoc.getName().split("\\.")[1];
			attrMap.put(name.toLowerCase(), assoc.getName());
			if (name.startsWith("_"))
				attrMap.put(name.substring(1).toLowerCase(), assoc.getName());
		}
		
		for(IMetaPrimitive prim : metaObject.getMetaPrimitives()) {
			String name = prim.getName();
			attrMap.put(name.toLowerCase(), name);
			if (name.startsWith("_"))
				attrMap.put(name.substring(1).toLowerCase(), name);
		}
		
		metaAttributeMaps.put(metaObject.getName(), attrMap);
		return attrMap;
	}

	@SuppressWarnings("deprecation")
	private static Object jsonAttributeToPrimitive(IMetaPrimitive primitive, JSONObject object, String attr) throws Exception {
		switch(primitive.getType()) {
		case Currency:
		case Float:
			if (object.isNull(attr))
				return null;
			return object.getDouble(attr);
		case Decimal:
			if (object.isNull(attr))
				return null;
			String asString = object.optString(attr, null);
			if (asString != null)
				return new BigDecimal(asString);
			return new BigDecimal(object.getDouble(attr));
		case Boolean:
			return object.getBoolean(attr);
		case DateTime: 
			if (object.isNull(attr))
				return null;
			return new Date(object.getLong(attr));
		case Enum:
			// support for built-in BooleanValue enumeration
			if ("RestServices.BooleanValue".equals(primitive.getEnumeration().getName())) {
				if(object.isNull(attr))
					return null;
				return object.getBoolean(attr) ? BooleanValue._true.toString() : BooleanValue._false.toString();
			}
			
			// fall-through intentional
		case HashString:
		case String:
			if (object.isNull(attr))
				return null;
			return object.getString(attr);
		case AutoNumber:
		case Long:
			if (object.isNull(attr))
				return null;
			return object.getLong(attr);
		case Integer:
			if (object.isNull(attr))
				return null;
			return object.getInt(attr);
		case Binary:
		default:
			throw new Exception("Unsupported attribute type '" + primitive.getType() + "' in attribute '" + attr + "'");
		}	
	}

}
