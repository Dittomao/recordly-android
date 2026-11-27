# TapSwoop — Home and Editor concept review

Open `index.html` in a browser. Use the direction switcher to compare the
same sample projects in three layouts. Desktop shows Home and Editor
together; on a phone, switch screens above the mockup or open a project.

- **Studio:** Dark, preview-first workspace; the Record and Import controls
  stay within thumb reach, and editing options open only on demand.
- **Pocket:** Light, resume-first Home; the Editor puts plain-language options
  underneath the selected tool.
- **Cut Room** (opens by default): New recording and Import sit directly
  below the Home title and description, above the Projects and Recordings tabs;
  the Editor keeps labeled tracks and the current edit controls visible.
  **New recording** expands from a red circle to a brighter red pill on
  hover, keyboard focus, or tap. The label sits beside the resting icon,
  and **Import** fills the remaining row without shifting during the animation.
  One tap plays the recording reveal before opening the demo prompt.
  Import uses the raised face, hover lift, and press travel of Assembly
  Required's **Create a room**, in the Cut Room palette.

Try opening a project, playing or scrubbing the illustrated preview, and
selecting an editing tool. **Split here** and **Add zoom** change the sample
timeline; Undo reverses those changes. Recording, import, and export only
show explanatory prompts. The artwork and projects are fictional, and no
changes are persisted.

These are the original review prototypes. Cut Room is now implemented in the
Android renderer with real project and recording data; the browser review
above still uses fictional fixtures. Capture consent, auto-zoom and export
continue through the existing renderer and Android host.
