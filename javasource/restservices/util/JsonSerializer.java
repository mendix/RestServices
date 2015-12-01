package restservices.util;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.proxies.BooleanValue;
import restservices.proxies.Primitive;
import restservices.publish.DataService;

import com.mendix.core.Core;
import com.mendix.core.objectmanagement.member.MendixEnum;
import com.mendix.core.objectmanagement.member.MendixObjectReference;
import com.mendix.core.objectmanagement.member.MendixObjectReferenceSet;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;

public class JsonSerializer {

	/**
	 * returns a json string containingURL if id is persistable or json object if with the json representation if the object is not. s
	 * @param rsr
	 * @param id
	 * @param useServiceUrls 
	 * @return
	 * @throws Exception 
	 */
	public static Object identifierToJSON(IContext context, IMendixIdentifier id, boolean useServiceUrls) throws Exception {
		return identifierToJSON(context, id, new HashSet<Long>(), useServiceUrls);
	}
	
	
	private static Object identifierToJSON(IContext context, IMendixIdentifier id, Set<Long> alreadySeen, boolean useServiceUrls) throws Exception {
		if (id == null)
			return null;
		
		if (alreadySeen.contains(id.toLong())) {
			RestServices.LOGUTIL.warn("ID already seen: " + id.toLong() + ", skipping serialization");
			return null;
		}
		alreadySeen.add(id.toLong());
		
		/* persistable object, generate url */
		if (Core.getMetaObject(id.getObjectType()).isPersistable()) {
			if (!useServiceUrls)
				return null;
			
			DataService service = RestServices.getServiceForEntity(id.getObjectType());
			if (service == null) {
				RestServices.LOGUTIL.warn("No RestService has been definied for type: " + id.getObjectType() + ", identifier could not be serialized");
				return null;
			}
		
			IMendixObject obj = Core.retrieveId(context, id); //Optimize: for refset use retrieve ids
			if (obj == null) {
				RestServices.LOGUTIL.warn("Failed to retrieve identifier: " + id + ", does the object still exist?");
				return null;
			}
			if (Utils.isValidKey(service.getKey(context, obj)))
				return service.getObjecturl(context, obj);

			return null;
		}
		
		/* Non persistable object, write the object itself */
		IMendixObject obj = Core.retrieveId(context, id); //Optimize: for refset use retrieve ids 
		if (obj == null) {
			RestServices.LOGUTIL.warn("Failed to retrieve identifier: " + id + ", does the object still exist?");
			return null;
		}
		else if (obj.getType().equals(Primitive.entityName)) {
			return writePrimitiveToJson(context, Primitive.initialize(context, obj));
		}
		else
			return writeMendixObjectToJson(context, obj, alreadySeen, useServiceUrls);
	}

	private static Object writePrimitiveToJson(IContext context, Primitive primitive) {
		if (primitive.getPrimitiveType() == null)
			throw new IllegalStateException("PrimitiveType attribute of RestServices.Primitive should be set");
		
		switch (primitive.getPrimitiveType()) {
			case Number:
				return primitive.getNumberValue();
			case String:
				return primitive.getStringValue();
			case _NULL:
				return JSONObject.NULL;
			case _Boolean:
				return primitive.getBooleanValue();
			default:
				throw new IllegalStateException("PrimitiveType attribute of RestServices.Primitive should be set");
		}
	}


	public static JSONObject writeMendixObjectToJson(IContext context, IMendixObject view) throws Exception {
		return writeMendixObjectToJson(context, view, false);
	}
	
	public static JSONObject writeMendixObjectToJson(IContext context, IMendixObject view, boolean useServiceUrls) throws Exception {
		return writeMendixObjectToJson(context, view, new HashSet<Long>(), useServiceUrls);
	}
	
	private static JSONObject writeMendixObjectToJson(IContext context, IMendixObject view, Set<Long> alreadySeen, boolean useServiceUrls) throws Exception {
		if (view == null)
			throw new IllegalArgumentException("Mendix to JSON conversion expects an object");
		
		if (!Utils.hasDataAccess(view.getMetaObject(), context))
			throw new IllegalStateException("During JSON serialization: Object of type '" + view.getType() + "' has no readable members for users with role(s) " + context.getSession().getUserRolesNames() + ". Please check the security rules");
		
		JSONObject res = new JSONObject();

		Map<String, ? extends IMendixObjectMember<?>> members = view.getMembers(context);
		for(java.util.Map.Entry<String, ? extends IMendixObjectMember<?>> e : members.entrySet())
			serializeMember(context, res, getTargetMemberName(context, view, e.getKey()), e.getValue(), view.getMetaObject(), alreadySeen, useServiceUrls);
		
		return res;
	}

	private static String getTargetMemberName(IContext context,
			IMendixObject view, String sourceAttr) {
		String name = Utils.getShortMemberName(sourceAttr);
		if (view.hasMember(name + "_jsonkey"))
			name = (String) view.getValue(context, name + "_jsonkey");
		if (name == null || name.trim().isEmpty())
			throw new IllegalStateException("During JSON serialization: Object of type '" + view.getType() + "', member '" + sourceAttr + "' has a corresponding '_jsonkey' attribute, but its value is empty.");
		
		return name;
	}


	@SuppressWarnings("deprecation")
	private static void serializeMember(IContext context, JSONObject target, String targetMemberName, 
			IMendixObjectMember<?> member, IMetaObject viewType, Set<Long> alreadySeen, boolean useServiceUrls) throws Exception {
		if (context == null)
			throw new IllegalStateException("Context is null");
	
		Object value = member.getValue(context);
		String memberName = member.getName();
		
		if (Utils.isSystemAttribute(memberName) || memberName.endsWith("_jsonkey")) {
			//skip
		}
		//Primitive?
		else if (!(member instanceof MendixObjectReference) && !(member instanceof MendixObjectReferenceSet)) {
			
			switch(viewType.getMetaPrimitive(member.getName()).getType()) {
			case AutoNumber:
			case Long:
			case Boolean:
			case Currency:
			case Float:
			case Integer:
				if (value == null) {
					// Numbers or bools could be null in json, technically. 
					// Mendix supports it as well. Technically.
					RestServices.LOGUTIL.warn("Got 'null' as value for primitive '" + targetMemberName + "'");
					target.put(targetMemberName, JSONObject.NULL);
				} else {	
					target.put(targetMemberName, value);
				}
				break;
			case Enum:
				//Support for built-in BooleanValue enumeration.
				MendixEnum me = (MendixEnum) member;
				if ("RestServices.BooleanValue".equals(me.getEnumeration().getName())) {
					if (BooleanValue._true.toString().equals(me.getValue(context)))
						target.put(targetMemberName, true);
					else if (BooleanValue._false.toString().equals(me.getValue(context)))
						target.put(targetMemberName, false);
					break;
				}
				
				//other enumeration, fall trough intentional
			case HashString:
			case String:
				if (value == null)
					target.put(targetMemberName, JSONObject.NULL);
				else
					target.put(targetMemberName, value);
				break;
			case Decimal:
				if (value == null)
					target.put(targetMemberName, JSONObject.NULL);
				else
					target.put(targetMemberName, value.toString());
				break;
			case DateTime:
				if (value == null)
					target.put(targetMemberName, JSONObject.NULL);
				else	
					target.put(targetMemberName, (((Date)value).getTime()));
				break;
			case Binary:
				break;
			default: 
				throw new IllegalStateException("Not supported Mendix Membertype for member " + memberName);
			}
		}
			
		/**
		 * Reference
		 */
		else if (member instanceof MendixObjectReference){
			if (value != null) 
				value = identifierToJSON(context, (IMendixIdentifier) value, alreadySeen, useServiceUrls);
			
			if (value == null)
				target.put(targetMemberName, JSONObject.NULL);
			else
				target.put(targetMemberName, value);
		}
		
		/**
		 * Referenceset
		 */
		else if (member instanceof MendixObjectReferenceSet){
			JSONArray ar = new JSONArray();
			if (value != null) {
				@SuppressWarnings("unchecked")
				List<IMendixIdentifier> ids = (List<IMendixIdentifier>) value;
				Utils.sortIdList(ids);
				for(IMendixIdentifier id : ids) if (id != null) {
					Object url = identifierToJSON(context, id, alreadySeen, useServiceUrls);
					if (url != null)
						ar.put(url);
				}
			}
			target.put(targetMemberName, ar);			
		}
		
		else
			throw new IllegalStateException("Unimplemented membertype " + member.getClass().getSimpleName());
	}
}
