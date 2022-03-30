/**
 * "DLS-unverify" plugin for Gerrit
 *
 *   Updated for Gerrit 3 notedb removal changes, tested against Gerrit 3.2.3
 *   Originally Written in Scala against Gerrit 2.15.3
 *
 * On any change to a topic or its components, remove all label:Verified votes from all open changes in the topic
 *(if an existing change has a topic, and that topic is changed, remove votes from both the old and new topics)
 *
* Handles the following cases:
* An existing change has its topic field edited (added, removed or changed) through the WebUI or REST API
* An existing change with a topic is abandoned or restored
* An existing change with a topic has a new, non-trivial patchset uploaded
* A new change with a topic is uploaded, and existing changes have that topic
*
* Does not handle the following cases:
* An existing change with a topic is deleted (Gerrit does not have a ChangeDeletedListener)
* 
* Converted form Scala to Java
*/

package uk.ac.diamond.daq.gerrit.plugin.unverify;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.annotations.Listen;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.DeleteVoteInput;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.client.ChangeKind;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.LabelInfo;
import com.google.gerrit.extensions.events.ChangeAbandonedListener;
import com.google.gerrit.extensions.events.ChangeRestoredListener;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.events.TopicEditedListener;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.account.AccountResolver.UnresolvableAccountException;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.inject.Inject;

@Listen
public class DLSUnverify
		implements TopicEditedListener, ChangeAbandonedListener, ChangeRestoredListener, RevisionCreatedListener {

	private static final Logger logger = LoggerFactory.getLogger(DLSUnverify.class);

	@Inject
	private OneOffRequestContext requestContext;

	@Inject
	private GerritApi gApi;

	@Inject
	private AccountResolver accountResolver;

	@Inject
	private PluginConfigFactory cfg;

	private DeleteVoteInput deleteOptions = new DeleteVoteInput();

	public DLSUnverify() {
		deleteOptions.label = "Verified";
		deleteOptions.notify = NotifyHandling.NONE;
	}

	@Override
	public void onRevisionCreated(com.google.gerrit.extensions.events.RevisionCreatedListener.Event event) {
		String existingTopic = Optional.ofNullable(event.getChange().topic).orElse("").trim();
		if (existingTopic.isEmpty()) {
			logger.debug("Account \"{}/{}\" uploaded patchset {} without a topic, so no unverify required",
					event.getWho().username, event.getWho().name, event.getChange()._number);
			return;
		}
		ChangeKind kind = event.getRevision().kind;
		if ((kind == ChangeKind.NO_CODE_CHANGE) || (kind == ChangeKind.NO_CHANGE)) {
			logger.debug("Account \"{}/{}\" uploaded patchset {} which is a trivial revision, so no unverify required",
					event.getWho().username, event.getWho().name, event.getChange()._number);
			return;
		}
		try {
			int totalChangesUnverified = 0;
			logger.info("Account \"{}/{}\" uploaded patchset {} with topic \"{}\"", event.getWho().username,
					event.getWho().name, event.getChange()._number, existingTopic);
			totalChangesUnverified += unverifyOpenChangesWithTopic(existingTopic);
			logger.info("Removed Label:Verified votes from {} changes", totalChangesUnverified);
		} catch (Exception e) {
			logger.error("Failed to unverify changes", e);
		}

	}

	@Override
	public void onChangeRestored(com.google.gerrit.extensions.events.ChangeRestoredListener.Event event) {
		String existingTopic = Optional.ofNullable(event.getChange().topic).orElse("").trim();
		if (existingTopic.isEmpty()) {
			logger.debug("Account \"{}/{}\" restored change {} without a topic, so no unverify required",
					event.getWho().username, event.getWho().name, event.getChange()._number);
			return;
		}
		try {
			int totalChangesUnverified = 0;
			logger.info("Account \"{}/{}\" restored change {} with topic \"{}\"", event.getWho().username,
					event.getWho().name, event.getChange()._number, existingTopic);
			totalChangesUnverified += unverifyOpenChangesWithTopic(existingTopic);
			logger.info("Removed Label:Verified votes from {} changes", totalChangesUnverified);
		} catch (RestApiException | ConfigInvalidException | IOException e) {
			logger.error("Failed to unverify changes", e);
		}
	}

	@Override
	public void onChangeAbandoned(com.google.gerrit.extensions.events.ChangeAbandonedListener.Event event) {
		String existingTopic = Optional.ofNullable(event.getChange().topic).orElse("").trim();
		if (existingTopic.isEmpty()) {
			logger.debug("Account \"{}/{}\" abandoned change {} without a topic, so no unverify required",
					event.getWho().username, event.getWho().name, event.getChange()._number);
			return;
		}
		try {
			int totalChangesUnverified = 0;
			logger.info("Account \"{}/{}\" abandoned change {} with topic \"{}\"", event.getWho().username,
					event.getWho().name, event.getChange()._number, existingTopic);
			totalChangesUnverified += unverifyOpenChangesWithTopic(existingTopic);
			logger.info("Removed Label:Verified votes from {} changes", totalChangesUnverified);
		} catch (RestApiException | ConfigInvalidException | IOException e) {
			logger.error("Failed to unverify changes", e);
		}
	}

	@Override
	public void onTopicEdited(com.google.gerrit.extensions.events.TopicEditedListener.Event event) {
		int totalChangesUnverified = 0;
		String oldTopic = Optional.ofNullable(event.getOldTopic()).orElse("").trim();
		String newTopic = Optional.ofNullable(event.getChange().topic).orElse("").trim();
		logger.info("Account \"{}/{}\" updated the topic in change {} from \"{}\" to \"{}\"", event.getWho().username,
				event.getWho().name, event.getChange()._number, oldTopic, newTopic);

		if (event.getChange().status != ChangeStatus.NEW) {
			logger.debug("change {} is not Open, so no unverify required", event.getChange()._number);
			return;
		}
		try {
			// if the topic was removed (rather than changed), then neither of the
			// statements below will unverify the change itself, so do that first
			if (newTopic.isEmpty()) {
				totalChangesUnverified = unverifySingleChange(event.getChange());
			}
			totalChangesUnverified += unverifyOpenChangesWithTopic(oldTopic);
			totalChangesUnverified += unverifyOpenChangesWithTopic(newTopic);
			logger.info("Removed Label:Verified votes from {} changes", totalChangesUnverified);
		} catch (IOException | RestApiException | ConfigInvalidException e) {
			logger.error("Failed to unverify changes", e);
		}
	}

	/**
	 * @return number of votes removed
	 * @throws IOException
	 * @throws ConfigInvalidException
	 * @throws UnresolvableAccountException
	 */
	private int unverifySingleChange(ChangeInfo c)
			throws UnresolvableAccountException, ConfigInvalidException, IOException {
		if (c == null) {
			logger.error("unverifySingleChange was passed a null ChangeInfo");
			// no votes removed
			return 0;
		}
		LabelInfo verifiedLabel = c.labels.get("Verified");
		if (verifiedLabel.all == null) {
			logger.error("Change {} - no Verified votes", c._number);
			// no votes removed
			return 0;
		}

		int unverifyCount = 0;

		for (ApprovalInfo a : verifiedLabel.all) {
			if ((a.value.equals(1)) || (a.value.equals(-1))) {
				logger.info("Change {} - removing Verified:{} vote by \"{}/{}\"", c._number, a.value, a.username,
						a.name);
				String botUsername = cfg.getFromGerritConfig("DLS-unverify").getString("gerrit-bot-username");
				Account.Id gerritDLSBot = accountResolver.resolve(botUsername).asUnique().account().id();
				try (ManualRequestContext ctx = requestContext.openAs(gerritDLSBot)) {
					gApi.changes().id(c.id).reviewer(a.username).deleteVote(deleteOptions);
					unverifyCount += 1;
				} catch (RestApiException e) {
					logger.error("FAILED Change {}: {}", c._number, e.getMessage());
				}

			} else {
				logger.debug("Change {} - no Verified vote by \"{}/{}\"", c._number, a.username, a.name);
			}
		}
		return unverifyCount;
	}

	private int unverifyOpenChangesWithTopic(String topicName)
			throws RestApiException, ConfigInvalidException, IOException {
		int changesUnverified = 0;
		if (Optional.ofNullable(topicName).orElse("").trim().isEmpty()) {
			return 0;
		}

		// find all the open changes in the topic that have Verified set (any non-zero
		// value)
		// this only finds changes that are visible to the user who triggered the
		// TopicEditedListener
		String query = // s"""status:open topic:"${topicName}" (label:Verified-1 OR
						// label:Verified+1)""";
				String.format("status:open topic:\"%s\" (label:Verified-1 OR label:Verified+1)", topicName);
		List<ChangeInfo> changesInTopicVerified = gApi.changes().query(query)
				.withOptions(ListChangesOption.DETAILED_LABELS, ListChangesOption.DETAILED_ACCOUNTS).get();

		if (changesInTopicVerified.size() == 0) {
			logger.debug("Topic \"{}\" does not have any open changes with Label:Verified", topicName);
			return 0;
		}

		if (changesInTopicVerified.size() > 40) {
			logger.warn(
					"Topic \"{}\" has {} open changes with Label:Verified - not proceeding, as possible internal error",
					topicName, changesInTopicVerified.size());
			return 0;
		}

		for (ChangeInfo c : changesInTopicVerified) {
			if (unverifySingleChange(c) > 0) {
				changesUnverified += 1;
			}
		}
		return changesUnverified;
	}

}
