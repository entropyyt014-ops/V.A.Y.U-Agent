/** The complete public tool allowlist. No task delegation or model endpoints. */
const coord = { type: 'integer', minimum: 0, maximum: 16384 };
const text = { type: 'string', maxLength: 20000 };
const define = (name, description, properties = {}, required = []) => ({
  name: `vayu_${name}`, description,
  inputSchema: { type: 'object', properties: { deviceId: { type: 'string', maxLength: 128 }, ...properties }, required, additionalProperties: false },
});
export const tools = [
  define('devices', 'List connected phones. Select deviceId explicitly if more than one phone is online.'),
  define('look', 'Observe screenshot and UI tree before acting. Image and touch coordinates use native screen pixels.'),
  define('screenshot', 'Capture the phone screen at native resolution.'),
  define('ui_tree', 'Read visible UI elements and native screen bounds.'),
  define('status', 'Check phone connectivity and direct-control mode.'),
  define('tap', 'Tap native screen coordinates, then return screenshot and UI tree.', { x: coord, y: coord }, ['x', 'y']),
  define('long_press', 'Long press native screen coordinates.', { x: coord, y: coord }, ['x', 'y']),
  define('swipe', 'Swipe between native screen coordinates.', { x: coord, y: coord, x2: coord, y2: coord, duration: { type: 'integer', minimum: 50, maximum: 3000 } }, ['x', 'y', 'x2', 'y2']),
  define('type_text', 'Replace text in a field at native screen coordinates.', { x: coord, y: coord, text }, ['x', 'y', 'text']),
  define('find_and_tap', 'Tap a visible element matched by its text.', { text }, ['text']),
  define('type_in_field', 'Type into a visible field matched by its hint.', { text, fieldHint: text }, ['text', 'fieldHint']),
  define('press_back', 'Press Android Back.'),
  define('press_home', 'Press Android Home.'),
  define('press_recents', 'Open Android Recents.'),
  define('open_app', 'Open an installed app by its name or package.', { app: { type: 'string', minLength: 1, maxLength: 256 } }, ['app']),
  define('open_url', 'Open a web URL in Chrome, then observe. This does not generate images or finish website tasks.', { url: { type: 'string', pattern: '^https?://', maxLength: 4096 } }, ['url']),
  define('list_apps', 'List launchable installed apps.'),
];
export function validateCall(name, args = {}) {
  const tool = tools.find(t => t.name === name);
  if (!tool) throw new Error('Unknown tool');
  if (!args || typeof args !== 'object' || Array.isArray(args)) throw new Error('Arguments must be an object');
  for (const key of Object.keys(args)) {
    const rule = tool.inputSchema.properties[key];
    const val = args[key];
    if (!rule) throw new Error(`Unknown argument: ${key}`);
    if (rule.type === 'integer' && (!Number.isInteger(val) || val < rule.minimum || val > rule.maximum)) throw new Error(`Invalid ${key}`);
    if (rule.type === 'string' && (typeof val !== 'string' || val.length > rule.maxLength || val.length < (rule.minLength || 0) || (rule.pattern && !new RegExp(rule.pattern).test(val)))) throw new Error(`Invalid ${key}`);
  }
  for (const key of tool.inputSchema.required) if (!(key in args)) throw new Error(`Missing ${key}`);
  return { ...args, type: name === 'vayu_look' ? 'screenshot_and_ui' : name.slice(5) };
}
