package tests;

import com.mendix.core.Core;
import org.junit.Assert;
import org.junit.Test;
import restservices.RestServices;
import restservices.consume.RestConsumeException;
import restservices.consume.RestConsumer;
import restservices.proxies.HttpMethod;
import restservices.publish.MicroflowService;

public class TimeoutTests extends TestBase {

    @Test
    public void testIdleTimeout() throws Exception {
        RestConsumer.setGlobalRequestSettings(null, 1000L);
        String url = RestServices.getAbsoluteUrl("ServiceWithLongOperation");

        new MicroflowService("Tests.ServiceWithLongOperation", "*", HttpMethod.GET, "Service with 1 minute operation");

        try {
            RestConsumer.request(Core.createSystemContext(), HttpMethod.GET, url, null, null, false);
        } catch (RestConsumeException rce) {
            Assert.assertTrue(rce.getResponseData().getBody().startsWith("java.net.SocketTimeoutException"));
        } finally {
            RestConsumer.setGlobalRequestSettings(null, 0L);
        }
    }
}