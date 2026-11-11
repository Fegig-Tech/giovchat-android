package app.giovchat.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.giovchat.core.GiovContact
import app.giovchat.core.GiovRequestUserInfo

private val EmailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

/** Whether the typed values satisfy every field the workspace asks for. */
internal fun preChatValid(fields: GiovRequestUserInfo, first: String, last: String, email: String, phone: String): Boolean =
    (!fields.firstName || first.trim().isNotEmpty()) &&
        (!fields.lastName || last.trim().isNotEmpty()) &&
        (!fields.email || EmailPattern.matches(email.trim())) &&
        (!fields.phone || phone.trim().isNotEmpty())

/**
 * The pre-chat form: asks only for the contact fields the workspace requires and the host has not supplied. The
 * button stays disabled until every required field is valid.
 */
@Composable
internal fun PreChatForm(fields: GiovRequestUserInfo, initial: GiovContact?, onSubmit: (GiovContact) -> Unit) {
    val look = LocalGiovLook.current
    val strings = look.strings
    var first by rememberSaveable { mutableStateOf(initial?.firstName.orEmpty()) }
    var last by rememberSaveable { mutableStateOf(initial?.lastName.orEmpty()) }
    var email by rememberSaveable { mutableStateOf(initial?.email.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(initial?.phone.orEmpty()) }
    val valid = preChatValid(fields, first, last, email, phone)
    val focus = LocalFocusManager.current

    fun submit() {
        if (!valid) return
        fun clean(value: String): String? = value.trim().ifEmpty { null }
        onSubmit(GiovContact(firstName = clean(first), lastName = clean(last), email = clean(email), phone = clean(phone)))
    }

    val order = buildList {
        if (fields.firstName) add("first")
        if (fields.lastName) add("last")
        if (fields.email) add("email")
        if (fields.phone) add("phone")
    }

    @Composable
    fun Field(name: String, tag: String, label: String, value: String, hint: String, onChange: (String) -> Unit, type: KeyboardType, caps: KeyboardCapitalization) {
        val isLast = order.lastOrNull() == name
        val fieldShape = RoundedCornerShape(10.dp)
        Column(Modifier.padding(bottom = 16.dp)) {
            BasicText(
                strings.required(label),
                style = look.style.text(TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = look.secondaryText), look.style.label),
                modifier = Modifier.padding(bottom = 6.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = look.style.text(TextStyle(fontSize = 14.sp, color = look.text), look.style.input),
                cursorBrush = SolidColor(look.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = caps,
                    keyboardType = type,
                    autoCorrectEnabled = false,
                    imeAction = if (isLast) ImeAction.Done else ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .background(look.inputFill, fieldShape)
                            .border(1.dp, look.inputBorder, fieldShape)
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty()) BasicText(hint, style = look.hintStyle)
                        inner()
                    }
                },
            )
        }
    }

    Column(
        Modifier
            .testTag("giov-pre-chat")
            .fillMaxSize()
            .background(look.style.form ?: look.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        BasicText(
            strings.preChatTitle,
            style = look.style.text(TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = look.text), look.style.formTitle),
            modifier = Modifier.padding(bottom = 24.dp).semantics { heading() },
        )
        if (fields.firstName) {
            Field("first", "giov-field-firstName", strings.firstName, first, strings.firstName, { first = it }, KeyboardType.Text, KeyboardCapitalization.Words)
        }
        if (fields.lastName) {
            Field("last", "giov-field-lastName", strings.lastName, last, strings.lastName, { last = it }, KeyboardType.Text, KeyboardCapitalization.Words)
        }
        if (fields.email) {
            Field("email", "giov-field-email", strings.email, email, "you@example.com", { email = it }, KeyboardType.Email, KeyboardCapitalization.None)
        }
        if (fields.phone) {
            Field("phone", "giov-field-phone", strings.phone, phone, "+1 555 000 0000", { phone = it }, KeyboardType.Phone, KeyboardCapitalization.None)
        }
        Spacer(Modifier.height(8.dp))
        val start = strings.startChat
        Box(
            Modifier
                .testTag("giov-start-chat")
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .alpha(if (valid) 1f else 0.45f)
                .background(look.buttonColor, RoundedCornerShape(10.dp))
                .semantics { contentDescription = start }
                .clickable(enabled = valid, role = Role.Button) { submit() },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(start, style = look.buttonTextStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
        }
    }
}
