# Change invocation
sed -i '' 's/AccountEditSheet(/AccountEditSheet(\n                vm = vm,/' app/src/main/java/com/ipdial/ui/screens/AccountsScreen.kt

# Replace AccountEditSheet definition
sed -i '' 's/fun AccountEditSheet(/fun AccountEditSheet(\n    vm: SipViewModel,/' app/src/main/java/com/ipdial/ui/screens/AccountsScreen.kt

# Change default values inside AccountEditSheet
sed -i '' 's/var label     by remember { mutableStateOf(existing?.label ?: "iCall BD") }/var label     by remember { mutableStateOf(existing?.label ?: "") }/' app/src/main/java/com/ipdial/ui/screens/AccountsScreen.kt
sed -i '' 's/var domain    by remember { mutableStateOf(existing?.domain ?: defaultDomain) }/var domain    by remember { mutableStateOf(existing?.domain ?: "") }/' app/src/main/java/com/ipdial/ui/screens/AccountsScreen.kt
sed -i '' 's/var codec     by remember { mutableStateOf(existing?.codec ?: PreferredCodec.G711U) }/var codec     by remember { mutableStateOf(existing?.codec ?: PreferredCodec.G729) }/' app/src/main/java/com/ipdial/ui/screens/AccountsScreen.kt

