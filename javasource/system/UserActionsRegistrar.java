package system;

import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;

import aQute.bnd.annotation.component.Component;
import aQute.bnd.annotation.component.Reference;

import com.mendix.core.Core;
import com.mendix.core.component.LocalComponent;
import com.mendix.core.component.MxRuntime;
import com.mendix.integration.Integration;

@Component(immediate = true, properties = {"event.topics:String=com/mendix/events/model/loaded"})
public class UserActionsRegistrar implements EventHandler
{
	private MxRuntime mxRuntime;
	private LocalComponent component;
	private Integration integration;
	
	@Reference
	public void setMxRuntime(MxRuntime runtime)
	{
		mxRuntime = runtime;
		mxRuntime.bundleComponentLoaded();
	}
	
	@Reference
	public void setIntegration(Integration integration)
	{
		this.integration = integration;
	}
	
	@Override
	public void handleEvent(Event event)
	{
		if (event.getTopic().equals(com.mendix.core.event.EventConstants.ModelLoadedTopic()))        
		{
			component = mxRuntime.getMainComponent();
			Core.initialize(component, integration);   
			component.actionRegistry().registerUserAction(communitycommons.actions.acquireLock.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.Base64Decode.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.Base64DecodeToFile.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.Base64Encode.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.Base64EncodeFile.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.Clone.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.commitInSeparateDatabaseTransaction.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.commitWithoutEvents.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.copyAttributes.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.DateTimeToLong.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.DecryptString.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.DeepClone.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.Delay.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.deleteAll.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.DuplicateFileDocument.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.DuplicateImageDocument.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.encryptMemberIfChanged.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.EncryptString.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.EndTransaction.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.EscapeHTML.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.executeMicroflowAsUser.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.executeMicroflowAsUser_1.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.executeMicroflowAsUser_2.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.executeMicroflowInBackground.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.executeMicroflowInBatches.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.FileDocumentFromFile.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.FileFromFileDocument.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.GenerateHMAC_SHA256_hash.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.GetApplicationUrl.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.getCreatedByUser.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.GetDefaultLanguage.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.GetFileContentsFromResource.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.getFileSize.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.getGUID.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.GetIntFromDateTime.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.getLastChangedByUser.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.getLockOwner.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.getOriginalValueAsString.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.GetRuntimeVersion.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.getTypeAsString.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.Hash.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.HTMLEncode.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.HTMLToPlainText.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.IsInDevelopment.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.Log.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.LongToDateTime.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.memberHasChanged.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.MergeMultiplePdfs.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.objectHasChanged.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.objectIsNew.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.OverlayPdfDocument.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.ParseDateTimeWithTimezone.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.RandomHash.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.RandomString.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.RandomStrongPassword.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.recommitInBatches.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.refreshClass.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.refreshClassByObject.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.RegexQuote.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.RegexReplaceAll.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.RegexTest.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.releaseAllInactiveLocks.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.releaseLock.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.retrieveURL.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.RunMicroflowAsyncInQueue.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.SimpleLog.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.StartTransaction.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.storeURLToFileDocument.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.StringFromFile.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.StringLeftPad.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.StringLength.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.StringRightPad.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.StringToFile.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.StringTrim.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.SubstituteTemplate.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.SubstituteTemplate2.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.ThrowException.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.ThrowWebserviceException.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.TimeMeasureEnd.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.TimeMeasureStart.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.waitForLock.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.XSSSanitize.class);
			component.actionRegistry().registerUserAction(communitycommons.actions.YearsBetween.class);
			component.actionRegistry().registerUserAction(restservices.actions.addCookieToNextRequest.class);
			component.actionRegistry().registerUserAction(restservices.actions.addCredentialsToNextRequest.class);
			component.actionRegistry().registerUserAction(restservices.actions.addHeaderToNextRequest.class);
			component.actionRegistry().registerUserAction(restservices.actions.addIfNoneMatchHeader.class);
			component.actionRegistry().registerUserAction(restservices.actions.appendParamToUrl.class);
			component.actionRegistry().registerUserAction(restservices.actions.appendSlashToUrl.class);
			component.actionRegistry().registerUserAction(restservices.actions.copyAttributes.class);
			component.actionRegistry().registerUserAction(restservices.actions.delete.class);
			component.actionRegistry().registerUserAction(restservices.actions.deserializeJsonToObject.class);
			component.actionRegistry().registerUserAction(restservices.actions.fetchChanges.class);
			component.actionRegistry().registerUserAction(restservices.actions.followChanges.class);
			component.actionRegistry().registerUserAction(restservices.actions.get.class);
			component.actionRegistry().registerUserAction(restservices.actions.get2.class);
			component.actionRegistry().registerUserAction(restservices.actions.getCollection.class);
			component.actionRegistry().registerUserAction(restservices.actions.getCollectionAsync.class);
			component.actionRegistry().registerUserAction(restservices.actions.GetNrOfIncomingConnections.class);
			component.actionRegistry().registerUserAction(restservices.actions.getRequestCookies.class);
			component.actionRegistry().registerUserAction(restservices.actions.getRequestHeader.class);
			component.actionRegistry().registerUserAction(restservices.actions.getResponseCookies.class);
			component.actionRegistry().registerUserAction(restservices.actions.getResponseHeader.class);
			component.actionRegistry().registerUserAction(restservices.actions.getRestBaseUrl.class);
			component.actionRegistry().registerUserAction(restservices.actions.getRestConsumeError.class);
			component.actionRegistry().registerUserAction(restservices.actions.GetTrackingState.class);
			component.actionRegistry().registerUserAction(restservices.actions.getWithParams.class);
			component.actionRegistry().registerUserAction(restservices.actions.isUrl.class);
			component.actionRegistry().registerUserAction(restservices.actions.isValidObjectKey.class);
			component.actionRegistry().registerUserAction(restservices.actions.LoadService.class);
			component.actionRegistry().registerUserAction(restservices.actions.post.class);
			component.actionRegistry().registerUserAction(restservices.actions.post2.class);
			component.actionRegistry().registerUserAction(restservices.actions.postWithResult.class);
			component.actionRegistry().registerUserAction(restservices.actions.publishDelete.class);
			component.actionRegistry().registerUserAction(restservices.actions.publishUpdate.class);
			component.actionRegistry().registerUserAction(restservices.actions.put.class);
			component.actionRegistry().registerUserAction(restservices.actions.RebuildServiceIndex.class);
			component.actionRegistry().registerUserAction(restservices.actions.registerCredentials.class);
			component.actionRegistry().registerUserAction(restservices.actions.registerNTCredentials.class);
			component.actionRegistry().registerUserAction(restservices.actions.request.class);
			component.actionRegistry().registerUserAction(restservices.actions.resetChangeTracking.class);
			component.actionRegistry().registerUserAction(restservices.actions.serializeObjectToJson.class);
			component.actionRegistry().registerUserAction(restservices.actions.ServiceConsistencyCheck.class);
			component.actionRegistry().registerUserAction(restservices.actions.setGlobalRequestSettings.class);
			component.actionRegistry().registerUserAction(restservices.actions.setResponseCookie.class);
			component.actionRegistry().registerUserAction(restservices.actions.setResponseHeader.class);
			component.actionRegistry().registerUserAction(restservices.actions.setResponseStatus.class);
			component.actionRegistry().registerUserAction(restservices.actions.setRestBasePath.class);
			component.actionRegistry().registerUserAction(restservices.actions.StartMicroflowServiceJava.class);
			component.actionRegistry().registerUserAction(restservices.actions.StartPublishesServicesJava.class);
			component.actionRegistry().registerUserAction(restservices.actions.throwRestServiceException.class);
			component.actionRegistry().registerUserAction(restservices.actions.unfollowChanges.class);
			component.actionRegistry().registerUserAction(restservices.actions.UnloadService.class);
			component.actionRegistry().registerUserAction(restservices.actions.urlEncode.class);
			component.actionRegistry().registerUserAction(system.actions.VerifyPassword.class);
			component.actionRegistry().registerUserAction(tests.actions.multiplefile.class);
			component.actionRegistry().registerUserAction(tests.actions.quote.class);
			component.actionRegistry().registerUserAction(unittesting.actions.ReportStepJava.class);
			component.actionRegistry().registerUserAction(unittesting.actions.RunAllUnitTests.class);
			component.actionRegistry().registerUserAction(unittesting.actions.RunAllUnitTestsWrapper.class);
			component.actionRegistry().registerUserAction(unittesting.actions.RunUnitTest.class);
			component.actionRegistry().registerUserAction(unittesting.actions.ThrowAssertionFailed.class);
			component.actionRegistry().registerUserAction(unittesting.actions.UpdateUnitTestList.class);
		}
	}
}