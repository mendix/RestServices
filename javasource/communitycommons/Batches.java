package communitycommons;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.connectionbus.requests.IRetrievalSchema;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IRemoveBatch;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation.DeleteBehaviourChild;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation.DeleteBehaviourParent;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;
import communitycommons.proxies.LogLevel;

public class Batches
{
	static final int BATCHSIZEREMOVE	= 10000;
	
	/**
	 * Please use XPath.create(Core.createSystemContext(), entityType).deleteAll();
	 * @param context
	 * @param entityType
	 * @return
	 * @throws CoreException
	 */
	@Deprecated
	public static boolean deleteAll(IContext context, String entityType) throws CoreException
	{
		IMetaObject meta = Core.getMetaObject(entityType);
		boolean hasdelete = false; 
		for(IMetaAssociation assoc : meta.getMetaAssociationsParent())
			if (assoc.getDeleteBehaviourParent() != DeleteBehaviourParent.DELETEPARENTBUTKEEPCHILDS) {
				hasdelete = true;
				break;
			}
		if (!hasdelete)
			for(IMetaAssociation assoc : meta.getMetaAssociationsChild())
				if (assoc.getDeleteBehaviourChild() != DeleteBehaviourChild.DELETECHILDBUTKEEPPARENTS) {
					hasdelete = true;
					break;
				}
		
		String query = "//" + entityType;
		long size = Core.retrieveXPathQueryAggregate(context, "count(" + query +  ")");
		int loop = (int) Math.ceil(((float)size) / ((float)Batches.BATCHSIZEREMOVE));
		
		Map<String, String> sort = new HashMap<String,String>();
		
		IRetrievalSchema schema = Core.createRetrievalSchema();
		schema.setMetaObjectName(entityType);
		List<IMendixObject> toremove = null;
		
		for (int i = 0; i < loop; i++) {
			if (hasdelete)
				toremove  = Core.retrieveXPathQuery(context, query, Batches.BATCHSIZEREMOVE, i * Batches.BATCHSIZEREMOVE, sort);
			else {
				schema.setAmount(Batches.BATCHSIZEREMOVE);
				schema.setOffset(0);
				toremove = Core.retrieveXPathSchema(context, query, schema, false);
			}
	
			IRemoveBatch batch = Core.removeBatch(context, entityType, Batches.BATCHSIZEREMOVE, true, false);
			for(IMendixObject g : toremove)
				batch.removeObject(g);
			batch.commit();
			toremove.clear(); 
		}
		
		size = Core.retrieveXPathQueryAggregate(context, "count(" + query +  ")");
		if (size == 0)
			return true;
		Logging.log("CommunityCommons", LogLevel.Warning, "[deleteAll] After delete all there are " + size + " objects remaining. This might be a result of the configured security or deletebehavior. ");
		return false;
	}
	/* Zie de batch functionality uit the random generator */

	/**
	 * Deprecated, please use build-in batching as provided by Mendix 4
	 * @param context
	 * @param uniqueBatchIdentifier
	 * @return
	 * @throws Exception
	 */
	@Deprecated
	public static boolean commitBatch(IContext context, String uniqueBatchIdentifier) throws Exception
	{
		HandleBatch handleBatch = HandleBatch._getInstance(context.getSession().getId().toString());
		return handleBatch.commitChangeBatch(uniqueBatchIdentifier);		
	}

	/**
	 * Deprecated, please use build-in batching as provided by Mendix 4
	 * @param context
	 * @param uniqueBatchIdentifier
	 * @param genericEntity
	 * @throws Exception
	 */
	@Deprecated
	public static void addToBatch(IContext context, String uniqueBatchIdentifier,
			IMendixObject genericEntity) throws Exception
	{
		HandleBatch handleBatch = HandleBatch._getInstance(context.getSession().getId().toString());
		handleBatch.createChangeBatch(context, genericEntity.getType(), uniqueBatchIdentifier, genericEntity);
	}
}
