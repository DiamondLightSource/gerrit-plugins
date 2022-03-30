package uk.ac.diamond.daq.gerrit.plugin.verify.trigger;

import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.inject.AbstractModule;
import com.google.gerrit.server.change.ChangeResource;

public class Module extends AbstractModule {
	@Override
	protected void configure() {
		install(new RestApiModule() {
			@Override
			protected void configure() {
				get(ChangeResource.CHANGE_KIND, "verifytrigger").to(DLSVerifyTrigger.class);
			}
		});
	}
}
