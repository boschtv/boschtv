@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package nl.boschtv.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.boschtv.tv.R
import nl.boschtv.tv.data.Profiles
import nl.boschtv.tv.data.db.ProfileEntity

/** What a profile can look like: an emoji or the name's first letter, on a gradient. Stored as the emoji (or [LETTER]) and the gradient's index. */
object Avatars {
    /** Stored instead of an emoji: the avatar shows the first letter of the profile's name. */
    const val LETTER = "Aa"

    /** Illustrated avatars shipped in assets/avatars/ (see THIRD_PARTY_NOTICES.md), stored as "art:<style>/<nn>". */
    private val art = listOf("bottts", "avataaars", "fun-emoji", "pixel-art")
        .map { style -> (1..16).map { "art:$style/" + it.toString().padStart(2, '0') } }

    /**
     * What the picker offers, one tab per group: the illustrated sets first, then emoji from Unicode 11 or
     * older (so they also show on older TVs). Each group fills two rows of the picker.
     */
    val groups: List<List<String>> = art + listOf(
        listOf("🦊", "🐼", "🐯", "🦁", "🐸", "🐙", "🦄", "🐻", "🐨", "🐶", "🐱", "🦉", "🐧", "🐵", "🦖", "🦈"),
        listOf("🐲", "👽", "🤖", "👻", "🎃", "🤡", "🧙", "🧛", "🧜", "🧚", "🦸", "🦹", "💀", "👾", "🧞", "🤠"),
        listOf("😎", "🤓", "🥳", "😺", "😈", "🤩", "🥶", "🤯", "😇", "🙃", "😴", "🤪", "🥰", "😜", "🤗", "😏"),
        listOf("🍕", "🍔", "🌮", "🍩", "🍦", "🍓", "🍉", "🍿", "🥑", "🍣", "🧁", "🍪", "🥨", "🍫", "🍒", "🥥"),
        listOf("⚽", "🏀", "🏈", "🎾", "🏐", "🎮", "🎲", "🎸", "🎧", "🎨", "🛹", "🏆", "🥊", "🎯", "🏓", "🎳"),
        listOf("🚀", "🌈", "🌵", "🌙", "⭐", "🔥", "⚡", "🌊", "🌸", "🌻", "🍀", "🌍", "❄️", "🌋", "🍄", "💎"),
    )
    val emoji: List<String> = groups.flatten()

    private val gradients = listOf(
        Color(0xFF2979FF) to Color(0xFF7C4DFF),   // royal blue to violet, the app's own
        Color(0xFFFF4081) to Color(0xFFFF9100),   // pink to orange
        Color(0xFF00BFA5) to Color(0xFF00B0FF),   // teal to sky
        Color(0xFFFFC400) to Color(0xFFFF6D00),   // gold to orange
        Color(0xFFAA00FF) to Color(0xFFFF4081),   // purple to pink
        Color(0xFF00C853) to Color(0xFFC6FF00),   // green to lime
        Color(0xFFFF1744) to Color(0xFF6A1B9A),   // red to plum
        Color(0xFF37474F) to Color(0xFF90A4AE),   // slate
        Color(0xFF1A237E) to Color(0xFF00E5FF),   // midnight to cyan
        Color(0xFF212121) to Color(0xFFFFB300),   // black to gold
    )
    val colors get() = gradients.indices

    fun brush(i: Int): Brush = gradients[i.mod(gradients.size)].let { Brush.linearGradient(listOf(it.first, it.second)) }

    /** The group a stored avatar belongs to, for opening the picker on the right tab; the letter is the last tab. */
    fun groupOf(avatar: String): Int = if (avatar == LETTER) groups.size else groups.indexOfFirst { avatar in it }.coerceAtLeast(0)

    /** Where an illustrated avatar's drawing is; null for an emoji or the letter. */
    fun asset(avatar: String): String? = avatar.removePrefix("art:").takeIf { avatar.startsWith("art:") }?.let { "file:///android_asset/avatars/$it.svg" }

    /** A new profile starts with an avatar and a colour no one on this TV has yet. */
    fun fresh(taken: List<ProfileEntity>): ProfileEntity {
        val emoji = emoji.firstOrNull { e -> taken.none { it.avatar == e } } ?: emoji.random()
        val color = colors.firstOrNull { c -> taken.none { it.color == c } } ?: colors.random()
        return ProfileEntity(name = "", avatar = emoji, color = color)
    }
}

/**
 * An emoji drawn once into its own layer. Android draws emoji as vector graphics, rebuilt whenever their
 * size or scale changes; without the layer, every focus animation redraws them frame by frame, which on a
 * TV holds up the screen for seconds.
 */
@Composable
fun Emoji(emoji: String, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    Text(emoji, style = TextStyle(fontSize = fontSize, lineHeight = fontSize, textAlign = TextAlign.Center),
        modifier = modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen })
}

/** An illustrated avatar, drawn once into a picture at the size it is shown and cached from then on. */
@Composable
private fun ArtImage(asset: String, modifier: Modifier) {
    coil.compose.AsyncImage(asset, null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Fit)
}

/** One choice in the picker: an illustrated avatar or an emoji, at [size]. */
@Composable
private fun Choice(avatar: String, size: Dp) {
    val art = Avatars.asset(avatar)
    if (art != null) ArtImage(art, Modifier.size(size).padding(size * 0.08f))
    else Emoji(avatar, with(LocalDensity.current) { (size * 0.62f).toSp() })
}

@Composable
fun Avatar(avatar: String, color: Int, size: Dp, modifier: Modifier = Modifier, name: String = "") {
    val letter = avatar == Avatars.LETTER
    val fontSize = with(LocalDensity.current) { (size * if (letter) 0.5f else 0.54f).toSp() }
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Avatars.brush(color)), contentAlignment = Alignment.Center) {
        val art = Avatars.asset(avatar)
        if (art != null) {
            ArtImage(art, Modifier.fillMaxSize().padding(size * 0.1f))
        } else if (letter) {
            Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = fontSize, lineHeight = fontSize, textAlign = TextAlign.Center))
        } else {
            Emoji(avatar, fontSize)
        }
    }
}

private val TILE = 112.dp

/**
 * "Who's watching?": pick a profile, add one, or switch to managing them. Shown when the app opens and
 * more than one person uses it, and from the avatar in the top bar. With [onClose] Back closes it;
 * without (when the app opens) Back leaves the app.
 */
@Composable
fun ProfilesScreen(onClose: (() -> Unit)?, onPicked: () -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val all by c.profiles.all.collectAsState()
    val active by c.profiles.active.collectAsState()
    var managing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProfileEntity?>(null) }
    // The tile the remote lands on: the one watching, or the one just made or changed.
    var focusId by remember { mutableStateOf(active?.id) }
    val list = all ?: return

    BackHandler(editing != null || managing || onClose != null) {
        when {
            editing != null -> editing = null
            managing -> managing = false
            else -> onClose?.invoke()
        }
    }

    Box(Modifier.fillMaxSize().background(Ink.Bg)) {
        val e = editing
        if (e != null) {
            ProfileEditor(e, canDelete = e.id != 0L && list.size > 1) { saved ->
                if (saved != null) focusId = saved
                editing = null
            }
            return@Box
        }
        val first = remember { FocusRequester() }
        LaunchedEffect(managing, list.size) { delay(80); runCatching { first.requestFocus() } }
        val target = list.firstOrNull { it.id == focusId }?.id ?: list.first().id
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(stringResource(if (managing) R.string.manage_profiles else R.string.whos_watching), style = MaterialTheme.typography.displayMedium, color = Ink.Text)
            Spacer(Modifier.height(44.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                for (p in list) {
                    ProfileTile(p, managing, if (p.id == target) Modifier.focusRequester(first) else Modifier) {
                        if (managing) editing = p
                        else scope.launch { c.profiles.select(p.id); onPicked() }
                    }
                }
                if (list.size < Profiles.MAX) AddTile { editing = Avatars.fresh(list) }
            }
            Spacer(Modifier.height(44.dp))
            Pill(
                stringResource(if (managing) R.string.done else R.string.manage_profiles), { managing = !managing },
                selected = true, icon = if (managing) Icons.Rounded.Check else Icons.Rounded.Edit,
            )
            Text(stringResource(R.string.profile_own), style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun ProfileTile(p: ProfileEntity, managing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(TILE), horizontalAlignment = Alignment.CenterHorizontally) {
        Tile(onClick, modifier.size(TILE).onFocusChanged { focused = it.isFocused },
            shape = RoundedCornerShape(TILE * 0.28f), scale = 1.1f, color = Color.Transparent, focusedColor = Color.Transparent) {
            Box(contentAlignment = Alignment.Center) {
                Avatar(p.avatar, p.color, TILE, name = p.name)
                if (managing) {
                    Box(Modifier.size(TILE).background(Color(0x8C0A0A24)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Edit, stringResource(R.string.edit_profile), tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
            }
        }
        Text(p.name, style = MaterialTheme.typography.titleMedium, color = if (focused) Ink.Text else Ink.Muted, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(TILE), horizontalAlignment = Alignment.CenterHorizontally) {
        Tile(onClick, Modifier.size(TILE).onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(TILE * 0.28f), scale = 1.1f) {
            Box(Modifier.size(TILE).border(2.dp, Ink.Line, RoundedCornerShape(TILE * 0.28f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(44.dp))
            }
        }
        Text(stringResource(R.string.add_profile), style = MaterialTheme.typography.titleMedium, color = if (focused) Ink.Text else Ink.Muted, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
    }
}

/** Name, emoji and colour of a profile; a new one when its id is 0. [onDone] gets the saved id, or null. */
@Composable
private fun ProfileEditor(start: ProfileEntity, canDelete: Boolean, onDone: (Long?) -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val isNew = start.id == 0L
    var name by remember { mutableStateOf(start.name) }
    var emoji by remember { mutableStateOf(start.avatar) }
    var color by remember { androidx.compose.runtime.mutableIntStateOf(start.color) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val nameField = remember { FocusRequester() }
    val save = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Always start at the name: the first thing anyone changes.
    LaunchedEffect(Unit) { delay(80); runCatching { nameField.requestFocus() } }
    val needName = stringResource(R.string.name_needed)

    Row(Modifier.fillMaxSize().padding(horizontal = 72.dp), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(emoji, color, 200.dp, name = name)
            Text(name.ifBlank { " " }, style = MaterialTheme.typography.headlineMedium, color = Ink.Text, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp, bottom = 22.dp))
            // The buttons sit under the preview: one press left from anywhere in the editor.
            val wide = Modifier.width(200.dp)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(stringResource(R.string.save), {
                    if (name.isBlank()) { note = needName; runCatching { nameField.requestFocus() } }
                    else scope.launch { onDone(c.profiles.save(start.copy(name = name, avatar = emoji, color = color))) }
                }, wide.focusRequester(save), primary = true, icon = Icons.Rounded.Check)
                Pill(stringResource(R.string.cancel), { onDone(null) }, wide, selected = true)
                if (canDelete) {
                    Pill(stringResource(if (confirmDelete) R.string.sure_again else R.string.delete_profile), {
                        if (confirmDelete) scope.launch { c.profiles.delete(start.id); onDone(null) } else confirmDelete = true
                    }, wide, selected = true, icon = Icons.Rounded.Delete)
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(if (isNew) R.string.new_profile else R.string.edit_profile), style = MaterialTheme.typography.headlineLarge, color = Ink.Text)
            Label(stringResource(R.string.profile_name))
            Field(name, { name = it.take(Profiles.MAX_NAME); note = null }, stringResource(R.string.profile_name), Modifier.width(440.dp).focusRequester(nameField), last = true,
                // The keyboard's ✓ goes straight to Save.
                onDone = { keyboard?.hide(); runCatching { save.requestFocus() } })
            Label(stringResource(R.string.avatar))
            // One group at a time: a row of tabs, then two rows of emoji (or the letter).
            var group by remember { androidx.compose.runtime.mutableIntStateOf(Avatars.groupOf(start.avatar)) }
            // Eleven tabs do not fit next to each other: the row scrolls along with the remote.
            androidx.compose.foundation.lazy.LazyRow(Modifier.width(440.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Avatars.groups.size + 1) { g ->
                    val label = if (g == Avatars.groups.size) Avatars.LETTER else Avatars.groups[g][0]
                    val last = g == Avatars.groups.size
                    Tile({ group = g; if (last) emoji = Avatars.LETTER },
                        Modifier.size(56.dp, 44.dp).then(if (last) Modifier.focusProperties { right = FocusRequester.Cancel } else Modifier),
                        shape = RoundedCornerShape(50), scale = 1.1f, color = if (g == group) Ink.SurfaceHi else Color.Transparent) {
                        Box(Modifier.size(56.dp, 44.dp), contentAlignment = Alignment.Center) {
                            if (last) Text(label, style = MaterialTheme.typography.titleLarge.copy(color = Ink.Text))
                            else Choice(label, 36.dp)
                        }
                    }
                }
            }
            if (group == Avatars.groups.size) {
                Tile({ emoji = Avatars.LETTER }, Modifier.size(104.dp), shape = RoundedCornerShape(20.dp), scale = 1.08f,
                    color = if (emoji == Avatars.LETTER) Ink.Accent else Ink.Surface, focusedColor = if (emoji == Avatars.LETTER) Ink.Accent else Ink.SurfaceHi) {
                    Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                        Text(name.trim().take(1).uppercase().ifEmpty { "A" }, style = MaterialTheme.typography.displayMedium, color = Color.White)
                    }
                }
            } else FlowRow(Modifier.width(440.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 8) {
                for (e in Avatars.groups[group]) {
                    Tile({ emoji = e }, Modifier.size(48.dp), shape = RoundedCornerShape(14.dp), scale = 1.15f,
                        color = if (e == emoji) Ink.Accent else Ink.Surface, focusedColor = if (e == emoji) Ink.Accent else Ink.SurfaceHi) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Choice(e, 48.dp)
                        }
                    }
                }
            }
            Label(stringResource(R.string.colour))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in Avatars.colors) {
                    Tile({ color = i }, Modifier.size(40.dp), shape = RoundedCornerShape(50), scale = 1.15f, color = Color.Transparent, focusedColor = Color.Transparent) {
                        Box(Modifier.size(40.dp).background(Avatars.brush(i), CircleShape), contentAlignment = Alignment.Center) {
                            if (i == color) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
            val hint = note ?: if (confirmDelete) stringResource(R.string.delete_profile_hint) else null
            hint?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Ink.AccentSoft) }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.Muted, modifier = Modifier.padding(top = 10.dp))
