#ifndef TT100K45_LABELS_H
#define TT100K45_LABELS_H

// Fixed class order from the trained TT100K45 checkpoint, not display aliases.
// Keep in sync with assets/tt100k45.labels.txt and assets/tt100k45.model.json.
static const char* const class_names[] = {
    "i2", "i4", "i5", "il100", "il60", "il80", "io", "ip",
    "p10", "p11", "p12", "p19", "p23", "p26", "p27", "p3", "p5", "p6",
    "pg", "ph4", "ph4.5", "ph5", "pl100", "pl120", "pl20", "pl30", "pl40",
    "pl5", "pl50", "pl60", "pl70", "pl80", "pm20", "pm30", "pm55",
    "pn", "pne", "po", "pr40", "w13", "w32", "w55", "w57", "w59", "wo"
};
static const int class_count = sizeof(class_names) / sizeof(class_names[0]);
static_assert(class_count == 45, "TT100K checkpoint requires exactly 45 labels");

#endif
