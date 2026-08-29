import re

with open('app/src/main/java/com/ipdial/ui/screens/AccountsScreen.kt', 'r') as f:
    content = f.read()

# Add imports
imports = """import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
"""
content = content.replace("import androidx.compose.material3.ExperimentalMaterial3Api", imports + "import androidx.compose.material3.ExperimentalMaterial3Api")

# Now add some logic to AccountEditSheet
logic_to_add = """    val savedLabels by vm.repo.savedLabels.collectAsState(initial = emptyList())
    val savedHosts by vm.repo.savedHosts.collectAsState(initial = emptyList())
    val suggestedLabels = listOf("iCallBD", "BanglaCall").plus(savedLabels).distinct()
    val suggestedHosts = listOf("103.129.202.202", "sip.amarip.net").plus(savedHosts).distinct()
    
    var expandedLabel by remember { mutableStateOf(false) }
    var expandedHost by remember { mutableStateOf(false) }
"""
content = content.replace("    var showPass  by remember { mutableStateOf(false) }", "    var showPass  by remember { mutableStateOf(false) }\n\n" + logic_to_add)

# Replace label OutlinedTextField
label_replacement = """
            ExposedDropdownMenuBox(
                expanded = expandedLabel,
                onExpandedChange = { expandedLabel = it }
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it; expandedLabel = true },
                    label = { Text("Display Name (e.g. Work, Home)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedLabel) }
                )
                if (suggestedLabels.isNotEmpty()) {
                    ExposedDropdownMenu(
                        expanded = expandedLabel,
                        onDismissRequest = { expandedLabel = false }
                    ) {
                        suggestedLabels.filter { it.contains(label, ignoreCase = true) || label.isEmpty() }.forEach { suggestion ->
                            DropdownMenuItem(
                                text = { Text(suggestion) },
                                onClick = {
                                    label = suggestion
                                    expandedLabel = false
                                }
                            )
                        }
                    }
                }
            }
"""
content = re.sub(
    r'OutlinedTextField\(\s*value = label, onValueChange = \{ label = it \},\s*label = \{ Text\("Display Name \(e\.g\. Work, Home\)"\) \},\s*singleLine = true, modifier = Modifier\.fillMaxWidth\(\)\s*\)',
    label_replacement.strip(),
    content
)

# Replace domain OutlinedTextField
domain_replacement = """
            ExposedDropdownMenuBox(
                expanded = expandedHost,
                onExpandedChange = { expandedHost = it }
            ) {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it; expandedHost = true },
                    label = { Text("SIP Domain / Server *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedHost) }
                )
                if (suggestedHosts.isNotEmpty()) {
                    ExposedDropdownMenu(
                        expanded = expandedHost,
                        onDismissRequest = { expandedHost = false }
                    ) {
                        suggestedHosts.filter { it.contains(domain, ignoreCase = true) || domain.isEmpty() }.forEach { suggestion ->
                            DropdownMenuItem(
                                text = { Text(suggestion) },
                                onClick = {
                                    domain = suggestion
                                    expandedHost = false
                                }
                            )
                        }
                    }
                }
            }
"""
content = re.sub(
    r'OutlinedTextField\(\s*value = domain, onValueChange = \{ domain = it \},\s*label = \{ Text\("SIP Domain / Server \*"\) \},\s*singleLine = true, modifier = Modifier\.fillMaxWidth\(\)\s*\)',
    domain_replacement.strip(),
    content
)

# Save onSave to add suggestions
onsave_regex = r'(onSave\( existing\?\.copy\([\s\S]*?\} ?: SipAccount\([\s\S]*?\) \))'
def onsave_repl(match):
    return """androidx.compose.runtime.rememberCoroutineScope().let { scope ->
                        scope.launch {
                            vm.repo.addSavedLabel(label)
                            vm.repo.addSavedHost(domain)
                        }
                    }
                    """ + match.group(1)

# Let's just modify the `onClick = {` of the Save button.
content = re.sub(
    r'(Button\(\s*onClick = \{\s*if \(username\.isNotBlank\(\) && password\.isNotBlank\(\) && domain\.isNotBlank\(\)\) \{)',
    r'\1\n                    androidx.compose.runtime.rememberCoroutineScope().let { scope ->\n                        /* handled in viewmodel or directly if scope is accessible, wait, rememberCoroutineScope is outside */',
    content
)

with open('app/src/main/java/com/ipdial/ui/screens/AccountsScreen.kt', 'w') as f:
    f.write(content)

