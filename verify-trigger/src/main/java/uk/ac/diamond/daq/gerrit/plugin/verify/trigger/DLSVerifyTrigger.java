/**
 * "DLS-verify-trigger" plugin for Gerrit
 *
 * Provides a REST API extension. When invoked, the Jenkins verify job for the
 * change/topic is triggered.
 *
 * Can be invoked from the WebUI.
 *
 *   Updated for Gerrit 3 notedb removal changes, tested against Gerrit 3.2.3
 *   Written against Gerrit 2.15.3
 *   
 *   Converted from Scala to Java
 */

package uk.ac.diamond.daq.gerrit.plugin.verify.trigger;

import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.entities.Change;
import com.google.common.io.ByteStreams;
import com.google.gerrit.entities.AccountGroup.UUID;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.config.CanonicalWebUrlProvider;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.inject.*;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DLSVerifyTrigger implements RestReadView<ChangeResource> {

	private static final Logger logger = LoggerFactory.getLogger(DLSVerifyTrigger.class);

	@Inject
	private PluginConfigFactory cfg;

	@Inject
	private CanonicalWebUrlProvider webUrl;

	@Override
	public Response<String> apply(ChangeResource resource)
			throws AuthException, BadRequestException, ResourceConflictException, Exception {

		CurrentUser user = resource.getUser();
		Change change = resource.getChange();

		// user must be authenticated (not anonymous)
		if (!(user.isIdentifiedUser())) {
			logger.warn("Request rejected - change={}, user not authenticated", change.getId());
			throw new AuthException("Request rejected - user not authenticated");
		}

		String userDesc = user.getAccountId() + "/" + user.asIdentifiedUser().getAccount().getName();
		// the change must be open
		if (!(change.getStatus().isOpen())) {
			logger.warn("Request rejected - change={} is not open, user={}", change.getId(), userDesc);
			throw new AuthException("Request rejected - change " + change.getId() + " is not open");
		}
		// the change must be in one of the appropriate repositories
		String projectName = change.getProject().get();
		if (!(validProjectPrefixes().stream().anyMatch(s -> projectName.startsWith(s)))) {
			logger.warn("Request rejected - change={} in non-triggerable project={}, user={}", change.getId(),
					projectName, userDesc);
			throw new AuthException(
					"Request rejected - change " + change.getId() + " is in a non-triggerable project " + projectName);
		}
		// User must be in an appropriate Group
		Set<String> groups = user.getEffectiveGroups().getKnownGroups().stream().map(UUID::get)
				.collect(Collectors.toSet()); // get UUIDs for groups the user is in
		if (groups.stream().noneMatch(validGroupUuids()::contains)) {
			logger.warn("Request rejected - change={}, user={} is not in an authorised group", change.getId(),
					userDesc);
			throw new AuthException("Request rejected - change " + change.getId() + ", user " + userDesc
					+ " is not in an authorised group");
		}

		String gerritHostname = (new URL(webUrl.get())).getHost();
		String jenkinsUrl = cfg.getFromGerritConfig("DLS-verify-trigger-" + gerritHostname)
				.getString("jenkins-job-url");
		String jenkinsToken = cfg.getFromGerritConfig("DLS-verify-trigger-" + gerritHostname)
				.getString("jenkins-job-token");
		String fullUrl = jenkinsUrl + "/buildWithParameters?token=" + jenkinsToken + "&gerrit_host=" + gerritHostname
				+ "&gerrit_change_number=" + change.getId() + "&gerrit_requesting_user=" + user.getAccountId()
				+ "&cause=Triggered-from-" + gerritHostname;

		try (InputStream in = new URL(fullUrl).openStream()) {
			ByteStreams.toByteArray(in);
		} catch (FileNotFoundException e) {
			logger.error("Failed getting URL (is Jenkins down?): {} ", fullUrl);
			throw new ResourceNotFoundException(
					"Failure triggering Jenkins test job (is Jenkins down?): " + jenkinsUrl);
		} catch (MalformedURLException e) {
			logger.error("Malformed Jenkins URL: {} ", fullUrl);
			throw new ResourceNotFoundException("Failure triggering Jenkins test job (malformed URL): " + jenkinsUrl);
		}

		logger.info("user={} triggered verify for change={}, project={}, topic={}", userDesc, change.getId(),
				projectName, change.getTopic());
		return Response.ok("");

	}

	/**
	 * only accept requests from users in one (or more) of these groups
	 */
	private List<String> validGroupUuids() {
		return Arrays.asList(cfg.getFromGerritConfig("DLS-verify-trigger").getStringList("permitted-group"));
	}

	/**
	 * only accept requests for changes in these projects
	 */
	private List<String> validProjectPrefixes() {
		return Arrays.asList(cfg.getFromGerritConfig("DLS-verify-trigger").getStringList("project-prefix"));
	}

}
