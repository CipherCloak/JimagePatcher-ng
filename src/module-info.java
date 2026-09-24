module jimagePatcher {
	// the jlink internals are only used through reflection, this makes sure that jdk.jlink is resolved
	requires jdk.jlink;
}
