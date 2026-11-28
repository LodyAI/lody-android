package app.lody.android.runtime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class RuntimeProtocolTest {
 private val fixtures=JSONObject(File(System.getProperty("runtime.fixtures")).readText())
 private fun operationMessages(items: String): List<SessionMessage> = (RuntimeProtocol.event(JSONObject().put("type", "session").put("sessionId", "s")
  .put("session", """{"v":1,"status":"live","entries":[{"id":"event","role":"system","items":$items}]}""")) as RuntimeEvent.SessionUpdated).snapshot.messages
 @Test fun operationProgressAndRepliesSurviveTheNativeSessionBoundary() {
  val progress = operationMessages("""[{"itemId":"progress","rev":1,"type":"operation_progress","operation":{"cards":[{"id":"0","kind":"created","sessionId":"child","label":"Research","status":"running"}],"notices":[]}}]""").single()
  assertEquals("system", progress.role)
  assertEquals(OperationCard("child", "Research", "running", null), progress.operations.single().cards.single())
  val reply = operationMessages("""[{"itemId":"receipt","rev":1,"type":"operation_completion","operation":{"cards":[{"id":"0","kind":"reply","sessionId":"child","status":"succeeded","reply":"The answer"}],"notices":[]}}]""").single().operations.single()
  assertFalse(reply.creates)
  assertEquals("The answer", reply.cards.single().reply)
 }
 @Test fun savedReplyRetainsFullMarkdownAndUpstreamTruncationIndependentlyOfDetail() {
  val full = "# Saved answer\n\n" + "Long reply with **formatting**.\n".repeat(40)
  val card = JSONObject().put("kind", "reply").put("sessionId", "deleted-target")
   .put("status", "succeeded").put("detail", "Separate detail").put("reply", full).put("replyTruncated", true)
  val item = JSONObject().put("type", "operation_completion").put("itemId", "saved")
   .put("operation", JSONObject().put("cards", org.json.JSONArray().put(card)))
  val decoded = operationMessages(org.json.JSONArray().put(item).toString()).single().operations.single().cards.single()
  assertEquals(full, decoded.reply)
  assertEquals("Separate detail", decoded.detail)
  assertTrue(decoded.replyTruncated)
  assertNull(app.lody.android.chat.SessionRelations.target(decoded.sessionId, emptyList()))
  card.remove("replyTruncated")
  assertFalse(operationMessages(org.json.JSONArray().put(item).toString()).single().operations.single().cards.single().replyTruncated)
 }
 @Test fun projectedSuppressionKeepsFailuresAndCancellationWithoutInventingTargets() {
  val events = operationMessages("""[{"itemId":"receipt","rev":1,"type":"operation_completion","operation":{"cards":[],"notices":[{"kind":"partial"},{"kind":"failed","detail":"Second: Machine offline"},{"kind":"cancelled","detail":"Third"}]}}]""").single().operations
  assertEquals(listOf("partial", "failed", "cancelled"), events.map { it.outcome })
  assertEquals("Second: Machine offline", events[1].error)
  assertTrue(events.all { it.cards.isEmpty() })
  assertTrue(operationMessages("""[{"itemId":"suppressed","rev":1,"type":"operation_completion","operation":{"cards":[],"notices":[]}}]""").isEmpty())
  val unknown = operationMessages("""[{"itemId":"batch","rev":1,"type":"operation_completion_batch","operation":{"cards":[],"notices":[{"kind":"batchUnknown","count":1}]}}]""").single().operations.single()
  assertTrue(unknown.deliveryUnknown)
  assertEquals(1, unknown.memberCount)
 }
 @Test fun batchProjectionPreservesMixedCreateReplyAndFailureDetails() {
  val events = operationMessages("""[{"itemId":"batch","rev":2,"type":"operation_completion_batch","operation":{"cards":[{"id":"a/0","kind":"created","sessionId":"first","status":"failed","detail":"Dispatch failed"},{"id":"b/0","kind":"reply","sessionId":"second","status":"succeeded","reply":"Finished"}],"notices":[{"kind":"batch","count":3},{"kind":"failed","detail":"Cannot dispatch"}]}}]""").single().operations
  assertEquals(3, events[0].memberCount)
  assertEquals("Cannot dispatch", events[1].error)
  assertTrue(events[2].creates)
  assertEquals("Dispatch failed", events[2].cards.single().detail)
  assertFalse(events[3].creates)
  assertEquals("Finished", events[3].cards.single().reply)
  assertTrue(operationMessages("""[{"type":"unknown"},{"type":"operation_completion"}]""").isEmpty())
 }
 @Test fun systemRowsCarryEditProposalsAndWebNoticesAndDropUnknownOnes() {
  val message = operationMessages("""[
   {"itemId":"edit","type":"system_notice","name":"schedule_edit_proposal","proposal":{"kind":"schedule_edit","proposalId":"p1","scheduleId":"sched","title":"Nightly","patch":{"title":"Morning","rule":{"kind":"weekdays","hour":8,"minute":0}},"baseActivityId":"a1","outcome":"applied"}},
   {"itemId":"fork","type":"system_notice","name":"session_fork_origin","meta":{"sourceSessionId":"source","sourceTitle":"Fix login"}},
   {"itemId":"resume","type":"system_notice","name":"resume_from_external_chat_history","meta":{"truncated":true}},
   {"itemId":"warn","type":"system_notice","name":"agent_warning","meta":{"source":"sessionStopTimedOut","message":"still running"}},
   {"itemId":"empty-warn","type":"system_notice","name":"agent_warning","meta":{}},
   {"itemId":"failed","type":"system_notice","name":"chat_failed","meta":{"reason":"agent_disconnected","message":"socket closed"}},
   {"itemId":"script","type":"worktree_script","phase":"setup","status":"failed","steps":[{"command":"pnpm build","status":"failed","output":"error"}]},
   {"itemId":"bad-script","type":"worktree_script","phase":"teardown","status":"failed","steps":[]},
   {"itemId":"broken","type":"system_notice","name":"schedule_edit_proposal","meta":{}},
   {"itemId":"future","type":"system_notice","name":"future_notice","meta":{"message":"hidden"}}
  ]""").single()
  val edit = message.proposals.single()
  assertTrue(edit.isEdit)
  assertEquals("applied", edit.outcome)
  assertEquals("sched", edit.scheduleId)
  assertEquals(ScheduleEditPatch("Morning", null, ScheduleRule("weekdays", hour = 8, minute = 0)), edit.patch)
  assertEquals(listOf(
   SystemNotice.ForkOrigin("fork", "source", "Fix login"),
   SystemNotice.ResumedHistory("resume", true),
   SystemNotice.Warning("warn", "sessionStopTimedOut", "still running"),
   SystemNotice.Failure("failed", "agent_disconnected", null, "socket closed"),
   SystemNotice.WorktreeScript("script", "setup", "failed", listOf(SystemNotice.ScriptStep("pnpm build", "failed", "error"))),
  ), message.notices)
  assertTrue(operationMessages("""[{"itemId":"future","type":"system_notice","name":"future_notice"}]""").isEmpty())
 }
 @Test fun anEditInspectionKeepsItsReasonAndDiff() {
  val live = ProposalInspection.parse(JSONObject("""{"state":"stale","isOwner":true,"currentTitle":"Nightly","currentRule":{"kind":"daily","hour":9,"minute":30,"timeZone":"UTC"},"timeZone":"UTC","promptDiff":[{"kind":"del","text":"a"},{"kind":"add","text":"b"},{"kind":"future","text":"c"}]}"""))
  assertEquals("stale", live.state)
  assertEquals(ScheduleRule("daily", hour = 9, minute = 30, timeZone = "UTC"), live.currentRule)
  assertEquals(listOf(PromptDiffLine("del", "a"), PromptDiffLine("add", "b")), live.promptDiff)
  assertNull(ProposalInspection.parse(JSONObject("""{"state":"unavailable","isOwner":false,"timeZone":"UTC"}""")).promptDiff)
 }
 @Test fun roostHistorySourceAndNewerPendingPublicationReachTheConversation() {
  val event = RuntimeProtocol.event(JSONObject().put("type", "session").put("sessionId", "s").put("historyStart", 24)
   .put("session", """{"v":1,"status":"live","entries":[],"historySync":{
    "source":"streams","phase":"retrying","httpStatus":503,"newerVersionPending":true
   }}""")) as RuntimeEvent.SessionUpdated
  assertEquals(RoostHistorySync(RoostHistorySync.Source.Streams, RoostHistorySync.Phase.Retrying, 503, true), event.snapshot.historySync)
  assertEquals(24, event.snapshot.historyStart)
 }
 @Test fun roostHistoryRequiringMachineAuthorizationShowsItsOwnPhase() {
  val event = RuntimeProtocol.event(JSONObject().put("type", "session").put("sessionId", "s")
   .put("session", """{"v":1,"status":"syncing","entries":[],"historySync":{
    "source":"streams","phase":"owner-required"
   }}""")) as RuntimeEvent.SessionUpdated
  assertEquals(RoostHistorySync(RoostHistorySync.Source.Streams, RoostHistorySync.Phase.OwnerRequired, null, false), event.snapshot.historySync)
  assertFalse(event.snapshot.controlReady)
 }
 @Test fun roostHistoryNoticeHidesHealthySourcesAndFormatsActionablePhases() {
  assertNull(app.lody.android.chat.historySyncNotice(RoostHistorySync(RoostHistorySync.Source.Machine, RoostHistorySync.Phase.Idle, null, false)))
  assertNull(app.lody.android.chat.historySyncNotice(RoostHistorySync(RoostHistorySync.Source.Streams, RoostHistorySync.Phase.Ready, null, false)))
  assertNull(app.lody.android.chat.historySyncNotice(RoostHistorySync(RoostHistorySync.Source.Machine, RoostHistorySync.Phase.Ready, null, true)))
  assertEquals(
   app.lody.android.chat.HistorySyncNotice(detailRes = app.lody.android.R.string.chat_history_sync_pending),
   app.lody.android.chat.historySyncNotice(RoostHistorySync(RoostHistorySync.Source.Streams, RoostHistorySync.Phase.Pending, null, false)),
  )
  assertEquals(
   app.lody.android.chat.HistorySyncNotice(previousRes = app.lody.android.R.string.chat_history_sync_previous),
   app.lody.android.chat.historySyncNotice(RoostHistorySync(RoostHistorySync.Source.Streams, RoostHistorySync.Phase.Ready, null, true)),
  )
  assertEquals(
   app.lody.android.chat.HistorySyncNotice(
    detailRes = app.lody.android.R.string.chat_history_sync_retrying_http,
    detailArg = 503,
    previousRes = app.lody.android.R.string.chat_history_sync_previous,
   ),
   app.lody.android.chat.historySyncNotice(RoostHistorySync(RoostHistorySync.Source.Streams, RoostHistorySync.Phase.Retrying, 503, true)),
  )
  assertEquals(
   app.lody.android.chat.HistorySyncNotice(detailRes = app.lody.android.R.string.chat_history_sync_denied),
   app.lody.android.chat.historySyncNotice(RoostHistorySync(RoostHistorySync.Source.Streams, RoostHistorySync.Phase.Refused, 403, false)),
  )
 }
 @Test fun usageProjectionPrefersContextThenFiveHours() {
  fun usage(json: String): SessionUsage? = (RuntimeProtocol.event(JSONObject().put("type", "session").put("sessionId", "s")
   .put("session", """{"v":1,"status":"live","entries":[],"usage":$json}""")) as RuntimeEvent.SessionUpdated).snapshot.usage
  val quota = """"quota":{"name":"Codex","windows":[{"duration":604800,"usedPercent":29},{"duration":18000,"usedPercent":0,"resetsAt":1900000000}]}"""
  assertFalse(usage("{}")!!.visible)
  assertFalse(usage("{$quota}")!!.visible)
  val eligible = usage("{\"quotaEligible\":true,\"modelId\":\"codex-model\",\"modelLabel\":\"Selected model\",$quota}")!!
  assertEquals("codex-model", eligible.modelId)
  assertEquals("Selected model", eligible.quota!!.name)
  assertEquals(0.0, eligible.triggerPercent!!, 0.0)
  assertEquals(listOf(604800.0, 18000.0), eligible.windows.map { it.duration })
  assertEquals(1900000000.0, eligible.windows.last().resetsAt!!, 0.0)
  val context = usage("{\"context\":{\"used\":250,\"size\":200},$quota}")!!
  assertEquals(100.0, context.triggerPercent!!, 0.0)
  assertEquals(250.0, context.context!!.used, 0.0)
  assertFalse(usage("{\"context\":{\"used\":20,\"size\":0}}")!!.visible)
  assertTrue(usage("{\"isContextCompacting\":true}")!!.visible)
  assertNull(usage("null"))
 }
 @Test fun malformedUsageDoesNotInventQuotaAndEmptyReportedPlanSurvives() {
  val parsed = SessionUsageJson.parse(JSONObject("""{"quotaEligible":true,"context":{"size":100,"used":-1},"quota":{"windows":[{"usedPercent":"20"},{"usedPercent":120,"duration":-3,"resetsAt":0}]}}"""))!!
  assertEquals(0.0, parsed.context!!.percent, 0.0)
  assertEquals(100.0, parsed.windows.single().usedPercent, 0.0)
  assertNull(parsed.windows.single().duration)
  assertNull(parsed.windows.single().resetsAt)
  val empty = SessionUsageJson.parse(JSONObject("""{"quotaEligible":true,"quota":{"windows":[]}}"""))!!
  assertNotNull(empty.quota)
  assertFalse(empty.visible)
 }
 @Test fun sessionTextKeepsOrderAndStreamingButOmitsToolPayloads() {
  val body="""{"v":1,"status":"live","entries":[{"id":"u","role":"user","finished":true,"items":[{"type":"text","text":"hello"}]},{"id":"a","role":"assistant","finished":false,"items":[{"type":"text","text":"First"},{"type":"tool_call","title":"private payload"},{"type":"text","text":"Second"},{"type":"proposed_plan","status":"cleared","markdown":"old plan"}]}]}"""
  val frame=JSONObject().put("type","session").put("sessionId","s").put("session",body)
  val snapshot=(RuntimeProtocol.event(frame) as RuntimeEvent.SessionUpdated).snapshot
  assertEquals("live",snapshot.status)
  assertEquals(listOf(SessionMessage("u","user","hello",false),SessionMessage("a","assistant","First\n\nSecond",true)),snapshot.messages.map { it.copy(items = emptyList()) })
 }
 @Test fun planApprovalCarriesItsPlanOptionsAndSettledOutcome() {
  val body="""{"v":1,"status":"live","entries":[{"id":"a","role":"assistant","finished":false,"items":[{"itemId":"p","type":"proposed_plan","status":"delta","markdown":"1. Draft"},{"itemId":"x","type":"tool_call","kind":"switch_mode","title":"Ready to code?","status":"pending","hasDetail":true,"plan":"1. Build","permission":{"requestId":"r","pending":true,"kind":"permission","options":[{"optionId":"yes","name":"Yes","kind":"allow_once"},{"optionId":"","name":"Broken"},{"optionId":"no","name":"No, keep planning","kind":"reject_once"}]}},{"itemId":"e","type":"tool_call","kind":"execute","title":"ls","status":"completed","plan":"ignored","hasDetail":false}]}]}"""
  val frame=JSONObject().put("type","session").put("sessionId","s").put("session",body)
  val items=(RuntimeProtocol.event(frame) as RuntimeEvent.SessionUpdated).snapshot.messages.single().items
  assertEquals(TurnItem.ProposedPlan("p","1. Draft",streaming=true),items[0])
  val approval=items[1] as TurnItem.Tool
  assertEquals("1. Build",approval.plan)
  assertTrue(approval.awaitingPermission)
  assertEquals(ToolPermission("r",true,listOf(PermissionOption("yes","Yes","allow_once"),PermissionOption("no","No, keep planning","reject_once"))),approval.permission)
  assertEquals("Only a plan approval carries a plan",null,(items[2] as TurnItem.Tool).plan)
  fun settled(fields: String) = (((RuntimeProtocol.event(JSONObject().put("type","session").put("sessionId","s").put("session",
   """{"v":1,"status":"live","entries":[{"id":"a","role":"assistant","finished":true,"items":[{"itemId":"x","type":"tool_call","kind":"switch_mode","title":"Ready to code?","status":"completed","permission":{"requestId":"r","pending":false,"kind":"permission","options":[{"optionId":"no","name":"No","kind":"reject_once"}]$fields}}]}]}"""))
   as RuntimeEvent.SessionUpdated).snapshot.messages.single().items.single() as TurnItem.Tool).permission!!)
  assertEquals("no",settled(",\"chosenOptionId\":\"no\"").chosenOptionId)
  assertFalse(settled(",\"chosenOptionId\":\"no\"").withdrawn)
  assertTrue(settled(",\"withdrawn\":true").withdrawn)
  assertNull(settled(",\"withdrawn\":true").chosenOptionId)
 }
 @Test fun askUserQuestionParsesItsQuestionsAndFailsClosedWhenMalformed() {
  fun permission(meta: String) = JSONObject().put("type","tool_call").put("itemId","q").put("kind","other").put("title","Ask").put("status","pending")
   .put("permission",JSONObject("{\"requestId\":\"r\",\"pending\":true,\"kind\":\"ask_user_question\",\"options\":[{\"optionId\":\"ok\",\"name\":\"Submit\",\"kind\":\"allow_once\"}]$meta}"))
  fun parse(meta: String): ToolPermission {
   val entry=JSONObject().put("id","a").put("role","assistant").put("finished",false).put("items",org.json.JSONArray().put(permission(meta)))
   val body=JSONObject().put("v",1).put("status","live").put("entries",org.json.JSONArray().put(entry))
   val event=JSONObject().put("type","session").put("sessionId","s").put("session",body.toString())
   return ((RuntimeProtocol.event(event) as RuntimeEvent.SessionUpdated).snapshot.messages.single().items.single() as TurnItem.Tool).permission!!
  }
  val good=parse(",\"questionMeta\":{\"source\":\"codex\",\"allowCustomAnswer\":false,\"questions\":[{\"id\":\"db\",\"header\":\"Database\",\"question\":\"Which one?\",\"options\":[{\"label\":\"Postgres\",\"description\":\"SQL\"}],\"multiSelect\":false,\"allowCustomAnswer\":true,\"isSecret\":true},{\"header\":\"Database\",\"question\":\"Extras?\",\"options\":[],\"multiSelect\":true}]}")
  assertTrue(good.question)
  val meta=good.questions!!
  assertEquals(QuestionMeta.Question("db","Database","Which one?",listOf(QuestionMeta.Option("Postgres","SQL")),false,true,true),meta.questions[0])
  assertEquals("Answers are keyed by id, else the unique question text",listOf("db","Extras?"),meta.questions.indices.map(meta::key))
  assertTrue("A question without options takes text",meta.allowsText(1))
  assertEquals("Missing metadata is not answerable",null,parse("").questions)
  assertEquals("An option without a label fails closed",null,parse(",\"questionMeta\":{\"questions\":[{\"header\":\"H\",\"question\":\"Q\",\"options\":[{\"description\":\"no label\"}],\"multiSelect\":false}]}").questions)
  assertEquals("No questions fails closed",null,parse(",\"questionMeta\":{\"questions\":[]}").questions)
  val answered=RuntimeProtocol.detail(JSONObject("{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"ok\",\"_meta\":{\"codex\":{\"requestUserInput\":{\"answers\":{\"db\":{\"answers\":[\"Postgres\"]}}}}}}}"))
  assertEquals(mapOf("db" to listOf("Postgres")),answered.answers)
  val suggested=ToolPermission("r",true,listOf(PermissionOption("always","Always","allow_always"),PermissionOption("once","Once","allow_once"),PermissionOption("no","No","reject_once")))
  assertEquals("Never suggests an always",  "once",suggested.suggested?.optionId)
  assertEquals("A refusal-first request suggests refusing","no",suggested.copy(options=suggested.options.reversed()).suggested?.optionId)
 }
 @Test fun recoveredInputsStayUnconfirmedAndDoNotDuplicateOwnerHistory() {
  val body = """{"v":1,"status":"live","entries":[{"id":"owner","role":"user","finished":true,"items":[{"type":"text","text":"confirmed"}]}],"pendingRoostInputs":[{"id":"owner","items":[{"type":"text","text":"duplicate"}]},{"id":"pending","timestamp":"2026-01-01","userId":"u","inputState":"unknown","items":[{"type":"text","text":"kept"},{"type":"file","file":{"fileName":"proof.txt"}}]}]}"""
  val frame = JSONObject().put("type", "session").put("sessionId", "s").put("session", body)
  val messages = (RuntimeProtocol.event(frame) as RuntimeEvent.SessionUpdated).snapshot.messages
  assertEquals(listOf("owner", "pending"), messages.map { it.id })
  assertEquals("delivery_unknown", messages.last().status)
  assertEquals("proof.txt", messages.last().attachments.single().name)
  assertEquals("kept", messages.last().text)
 }
 @Test fun fileOnlyTurnsRetainAllFilesAndStats() {
  val files = org.json.JSONArray()
  repeat(9) { files.put(JSONObject().put("path", "src/file$it.kt").put("add", it).put("del", 2)) }
  files.put(JSONObject().put("path", ""))
  val entry = JSONObject().put("id", "a").put("role", "assistant").put("finished", true).put("items", org.json.JSONArray()).put("fileDiffs", files)
  val body = JSONObject().put("v", 1).put("status", "live").put("entries", org.json.JSONArray().put(entry))
  val event = JSONObject().put("type", "session").put("sessionId", "s").put("session", body.toString())
  val messages = (RuntimeProtocol.event(event) as RuntimeEvent.SessionUpdated).snapshot.messages
  assertEquals(9, messages.single().files.size)
  assertEquals(FileChange("src/file8.kt", 8, 2), messages.single().files.last())
 }
 @Test fun composerRoleIsKnownOnlyWhenTheLatestTurnSaysSo() {
  fun composer(json: String) = (RuntimeProtocol.event(JSONObject().put("type","session").put("sessionId","s")
   .put("session","""{"v":1,"status":"live","composer":$json,"entries":[]}""")) as RuntimeEvent.SessionUpdated).snapshot.composer!!
  assertEquals(RunChoice(agentRoleId = "r", roleKnown = true), composer("""{"agentRoleId":"r"}"""))
  assertEquals(RunChoice(roleKnown = true), composer("""{"agentRoleId":null}"""))
  assertEquals(RunChoice(), composer("""{}"""))
 }
 @Test fun composerChoiceAndUserAttachmentsAreParsed() {
  val body="""{"v":1,"status":"live","composer":{"modelId":"gpt","modeId":"plan","effort":"high","configOptionValues":{"fast":true}},"entries":[{"id":"u","role":"user","finished":true,"items":[{"type":"image_group","images":[{"id":"i1","fileName":"a.png"},{"id":"i2","fileName":"b.png"}]},{"type":"file","file":{"id":"f1","fileName":"notes.md","storageSessionId":"parent","transport":"r2","sizeBytes":12}}]}]}"""
  val snapshot=(RuntimeProtocol.event(JSONObject().put("type","session").put("sessionId","s").put("session",body)) as RuntimeEvent.SessionUpdated).snapshot
  // Agent switches (Fast) come back so the chip and sheet reopen on them; no Role was said.
  assertEquals(RunChoice("gpt","plan","high",mapOf("fast" to true)),snapshot.composer)
  // An attachment-only user turn stays in the transcript; pictures keep their image and
  // uploaded files what opening them needs: id, storing session, transport and size.
  val notes=app.lody.android.chat.SessionFileRef("f1","notes.md","parent","r2",12)
  assertEquals(listOf(
   MessageAttachment("a.png",true,TurnImage("i1","a.png",null,null,null)),
   MessageAttachment("b.png",true,TurnImage("i2","b.png",null,null,null)),
   MessageAttachment("notes.md",false,file=notes),
  ),snapshot.messages.single().attachments)
  assertTrue(notes.downloadable && notes.markdown)
  assertFalse(notes.copy(transport="local").downloadable)
 }
 @Test fun userImageAttachmentsKeepTheirSessionImage() {
  val body="""{"v":1,"status":"live","entries":[{"id":"u","role":"user","finished":true,"items":[{"type":"image","image":{"id":"i1","fileName":"a.png","storageSessionId":"shared","width":600,"height":400}},{"type":"image","image":{"fileName":"no-id.png"}},{"type":"file","file":{"id":"f1","fileName":"notes.md"}}]}]}"""
  val snapshot=(RuntimeProtocol.event(JSONObject().put("type","session").put("sessionId","s").put("session",body)) as RuntimeEvent.SessionUpdated).snapshot
  // An image the server can address renders as a picture; one without an id stays a chip.
  assertEquals(listOf(
   MessageAttachment("a.png",true,TurnImage("i1","a.png","shared",600,400)),
   MessageAttachment("no-id.png",true),
   MessageAttachment("notes.md",false,file=app.lody.android.chat.SessionFileRef("f1","notes.md")),
  ),snapshot.messages.single().attachments)
 }
 @Test fun unaddressableImagesStayChipsAndBadSizesAreDropped() {
  val body="""{"v":1,"status":"live","entries":[{"id":"u","role":"user","finished":true,"items":[{"type":"image","image":{"id":" ","fileName":"blank.png"}},{"type":"image","image":{"id":"i2","fileName":"odd.png","storageSessionId":" ","width":0,"height":-3}},{"type":"image_group","images":[{"fileName":"group-a.png"},{"id":"i3","fileName":"group-b.png","width":640,"height":0}]}]}]}"""
  val snapshot=(RuntimeProtocol.event(JSONObject().put("type","session").put("sessionId","s").put("session",body)) as RuntimeEvent.SessionUpdated).snapshot
  // A blank id is no address, so the picture stays a chip; a group mixes both by its own ids.
  // Zero or negative sizes and a blank storing session are dropped rather than carried.
  assertEquals(listOf(
   MessageAttachment("blank.png",true),
   MessageAttachment("odd.png",true,TurnImage("i2","odd.png",null,null,null)),
   MessageAttachment("group-a.png",true),
   MessageAttachment("group-b.png",true,TurnImage("i3","group-b.png",null,640,null)),
  ),snapshot.messages.single().attachments)
 }
 @Test fun sessionProjectionIsNotDropped() {
  val frame=JSONObject().put("type","session").put("sessionId","s").put("session", """{"v":1,"status":"live","entries":[]}""")
  assertNotNull(RuntimeProtocol.event(frame))
 }
 @Test fun sessionHistoryPageStateIsProjected() {
  val frame=JSONObject().put("type","session").put("sessionId","s").put("historyStart",24)
   .put("session", """{"v":1,"status":"live","entries":[],"historyHasMore":true,"historyLoading":true}""")
  val snapshot=(RuntimeProtocol.event(frame) as RuntimeEvent.SessionUpdated).snapshot
  assertTrue(snapshot.historyHasMore)
  assertTrue(snapshot.historyLoading)
  assertEquals(24,snapshot.historyStart)
 }
 @Test fun sessionCommandsCanBeReadyDuringHistorySync() {
  val frame=JSONObject().put("type","session").put("sessionId","s").put("session",
   """{"v":1,"status":"syncing","entries":[],"controlReady":true,"sendReady":false}""")
  val snapshot=(RuntimeProtocol.event(frame) as RuntimeEvent.SessionUpdated).snapshot
  assertEquals("syncing",snapshot.status)
  assertTrue(snapshot.controlReady)
  assertFalse(snapshot.sendReady)
 }
 @Test fun sharedFixturesDecodeToTypedResults() {
  assertEquals(setOf("ping","setActive","probeSchema"),RuntimeProtocol.capabilities(fixtures.getJSONObject("hostReady")))
  val event=RuntimeProtocol.event(fixtures.getJSONObject("catalog")) as RuntimeEvent.CatalogUpdated
  assertEquals("中文 😀",event.snapshot.sessions.single().title);assertEquals(7L,event.snapshot.revision)
  assertEquals(RuntimeEvent.Synced(7,2),RuntimeProtocol.event(fixtures.getJSONObject("synced")))
  assertEquals(RuntimeEvent.GrantRequested,RuntimeProtocol.event(fixtures.getJSONObject("grant")))
  assertEquals(setOf("m"),RuntimeProtocol.schema(fixtures.getJSONObject("schema")).machineIds)
 }
 @Test fun networkFailureCannotBeMistakenForLiveAndDoesNotLeakRemoteText() {
  assertEquals(RuntimeEvent.SyncFailed("network_unavailable"),RuntimeProtocol.event(JSONObject("""{"type":"networkStatus","status":"unavailable"}""")))
  assertEquals(RuntimeEvent.SyncFailed("sync_failed"),RuntimeProtocol.event(JSONObject("""{"type":"syncError","reason":"private server contents"}""")))
 }
 @Test fun incompatibleOrIncompleteHandshakeFailsClosed() {
  assertThrows(Exception::class.java){RuntimeProtocol.capabilities(JSONObject("""{"protocol":1,"capabilities":["ping"]}"""))}
  assertThrows(Exception::class.java){RuntimeProtocol.capabilities(JSONObject("""{"protocol":2,"capabilities":["ping"]}"""))}
 }
 @Test fun malformedRequiredFieldsAreRejectedButFutureEventsIgnored() {
  assertThrows(Exception::class.java){RuntimeProtocol.event(JSONObject("""{"type":"synced","revision":"7","streams":2}"""))}
  assertThrows(Exception::class.java){RuntimeProtocol.event(JSONObject("""{"type":"catalog","revision":7,"catalog":{}}"""))}
  assertNull(RuntimeProtocol.event(JSONObject("""{"type":"futureEvent"}""")))
 }
 @Test fun browseFilesKeepsSafeEntriesFoldersFirstAndMapsFileOutcomes() {
  val listing = RuntimeProtocol.directory(JSONObject("""{"truncated":true,"entries":[{"name":"b.md","type":"file"},{"name":"src","type":"directory"},{"name":"A.md","type":"file"},{"name":"..","type":"directory"},{"name":"x/y","type":"file"},{"name":"link","type":"symlink"},{"type":"file"}]}"""))
  assertEquals(listOf(DirectoryEntry("src", true), DirectoryEntry("A.md", false), DirectoryEntry("b.md", false)), listing.entries)
  assertTrue(listing.truncated)
  assertThrows(IllegalArgumentException::class.java) { RuntimeProtocol.directory(JSONObject("{}")) }
  assertEquals(FileContent.Text("docs/a.md", "# A\n", 4), RuntimeProtocol.file(JSONObject("""{"status":"ok","path":"docs/a.md","kind":"text","text":"# A\n","bytes":4}"""), "docs/a.md"))
  assertEquals(FileContent.Binary("logo.png", 8, null, "iVBORw0KGgo="), RuntimeProtocol.file(JSONObject("""{"status":"ok","path":"logo.png","kind":"binary","base64":"iVBORw0KGgo=","bytes":8}"""), "logo.png"))
  assertEquals(FileContent.Failed("big.md", "too_large"), RuntimeProtocol.file(JSONObject("""{"status":"error","path":"big.md","code":"too_large"}"""), "big.md"))
  assertEquals(FileContent.Failed("odd.md", "read_failed"), RuntimeProtocol.file(JSONObject("""{"status":"ok","kind":"text"}"""), "odd.md"))
 }
 @Test fun catalogCarriesTheStatusViewLayoutAndPinSections() {
  val session = """{"id":"s","machineId":"m","projectId":"p","title":"t","status":"idle","archived":false,"pinned":true,"createdAt":"2026-01-01T00:00:00Z","orderKey":"a0","pinSectionId":"x","snoozedUntil":null}"""
  val layout = """{"pinned":[{"sectionId":"x","name":"Ops","rows":[{"id":"s","tier":"pinned","children":[],"activityAt":3,"unread":false,"snoozedUntil":null,"woken":false,"canManage":true,"canPrefer":true}],"hiddenCount":0}],"focus":[],"later":[],"settled":[],"nextChangeAt":null}"""
  fun catalog(view: String) = (RuntimeProtocol.event(JSONObject().put("type", "catalog").put("revision", 1).put("catalog",
   """{"projects":[],"sessions":[$session],"machineIds":["m"],"pinSections":[{"id":"x","name":"Ops","orderKey":"a0"},{"name":"no id"}],"statusView":$view}""")) as RuntimeEvent.CatalogUpdated).snapshot
  val snapshot = catalog("""{"mine":$layout,"team":$layout}""")
  assertEquals(listOf(PinSection("x", "Ops", "a0")), snapshot.pinSections)
  assertEquals("s", snapshot.statusView?.team?.pinned?.single()?.rows?.single()?.id)
  assertEquals("x", snapshot.sessions.single().pinSectionId)
  assertEquals("a0", snapshot.sessions.single().orderKey)
  // A layout this client cannot read leaves the catalog usable without one.
  val unreadable = catalog("""{"mine":$layout}""")
  assertEquals(null, unreadable.statusView)
  assertEquals(1, unreadable.sessions.size)
 }
 @Test fun previewsCrossTheNativeBoundary() {
  val token = RuntimeProtocol.event(JSONObject().put("type", "machineToken").put("operation", "historyToken").put("requestId", "r").put("intent", "{\"workspaceId\":\"w\"}"))
  assertEquals(RuntimeEvent.MachineTokenRequested("r", "historyToken", "{\"workspaceId\":\"w\"}"), token)
  val session = RuntimeProtocol.event(JSONObject().put("type", "session").put("sessionId", "s")
   .put("session", """{"v":1,"status":"live","entries":[],"preview":{"label":"localhost:5173","active":true}}""")) as RuntimeEvent.SessionUpdated
  assertEquals(BrowserPreview("localhost:5173", true), session.snapshot.preview)
  val catalog = RuntimeProtocol.event(JSONObject().put("type", "catalog").put("revision", 1).put("catalog",
   """{"projects":[],"sessions":[{"id":"s","machineId":"m","projectId":"p","title":"t","status":"idle","archived":false,"pinned":false,"createdAt":"2026-01-01T00:00:00Z","androidEmulatorPreviewRequestId":"req"}],"machineIds":["m","n"],"machineSimulators":{"m":"upgrade-required","n":"bogus"},"machineEmulators":{"n":"available"}}""")) as RuntimeEvent.CatalogUpdated
  assertEquals(mapOf("m" to "upgrade-required"), catalog.snapshot.machineSimulators)
  assertEquals(mapOf("n" to "available"), catalog.snapshot.machineEmulators)
  assertEquals("req", catalog.snapshot.sessions.single().androidEmulatorPreviewRequestId)
  val reply = PreviewJson.reply(DevicePlatform.ANDROID, JSONObject("""{"success":true,"devices":[{"id":"Pixel_8","name":"Pixel 8","runtime":"API 35","deviceType":"phone","state":"Shutdown","available":true,"occupancy":"other-session"},{"udid":"ios-only"}],
   "preview":{"operationId":"op","id":"Pixel_8","phase":"ready","transport":"remote","viewerUrl":"https://a-b.trycloudflare.com/v"}}"""))
  assertEquals(listOf("Pixel_8"), reply.devices?.map { it.id })
  assertEquals("other-session", reply.devices?.single()?.occupancy)
  assertEquals("ready", reply.preview?.phase)
  assertEquals(JSONObject().put("action", "start").put("udid", "U").toString(), PreviewJson.command(DevicePlatform.IOS, DeviceCommand.Start("U", "clear")).toString())
  assertEquals("saver", PreviewJson.command(DevicePlatform.ANDROID, DeviceCommand.Start("Pixel_8", "saver")).getString("quality"))
 }

 @Test fun turnDiffRetainsItsBaseAndCounts() {
  val diff = RuntimeProtocol.diff(JSONObject("""{"status":"ok","base":"turn","path":"a.kt","old":{"kind":"text","text":"a\n"},"new":{"kind":"text","text":"b\n"},"add":1,"del":1}"""))
  assertEquals("turn", diff.base)
  assertEquals("a\n", diff.oldText)
  assertEquals("b\n", diff.newText)
  assertEquals(1, diff.added)
  assertEquals(1, diff.removed)
  val current = RuntimeProtocol.diff(JSONObject("""{"status":"ok","base":"current","path":"a.kt","old":{"kind":"missing"},"new":{"kind":"text","text":"b\n"}}"""))
  assertEquals("current", current.base)
  assertEquals("", current.oldText)
 }

 @Test fun anOversizedDiffSideIsTooLargeRatherThanBinary() {
  val diff = RuntimeProtocol.diff(JSONObject("""{"status":"ok","base":"current","path":"big.kt","old":{"kind":"text","text":"a\n"},"new":{"kind":"too_large"}}"""))
  assertTrue(diff.tooLarge)
  assertNull(diff.newText)
  val binary = RuntimeProtocol.diff(JSONObject("""{"status":"ok","base":"current","path":"logo.png","old":{"kind":"missing"},"new":{"kind":"binary"}}"""))
  assertFalse(binary.tooLarge)
 }
 @Test fun diffKeepsAnOversizedSideDistinctFromBinary() {
  val oversized = RuntimeProtocol.diff(JSONObject("""{"status":"ok","path":"big.ts","old":{"kind":"text","text":"a"},"new":{"kind":"too_large"}}"""))
  assertTrue(oversized.tooLarge)
  assertNull(oversized.unavailable)
  val binary = RuntimeProtocol.diff(JSONObject("""{"status":"ok","path":"logo.png","old":{"kind":"binary"},"new":{"kind":"binary"}}"""))
  assertFalse(binary.tooLarge)
 }
 @Test fun workspaceChangesKeepOptionalCountsAndKinds() {
  val ready = RuntimeProtocol.workspaceChanges(JSONObject("""{"status":"ok","base":"abc","files":[
   {"path":"src/b.kt","add":4,"del":2,"kind":"modified"},{"path":"new.txt","kind":"added"},{"path":""}]}"""))
  assertEquals(
   WorkspaceChanges.Ready(listOf(
    WorkspaceChange("src/b.kt", 4, 2, "modified"),
    WorkspaceChange("new.txt", null, null, "added"),
   )),
   ready,
  )
  assertEquals(
   WorkspaceChanges.Unavailable("offline"),
   RuntimeProtocol.workspaceChanges(JSONObject("""{"status":"unavailable","reason":"offline"}""")),
  )
 }
}
