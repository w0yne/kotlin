var y: Int = 1

// No backing field!
<!MUST_BE_INITIALIZED!>var x: Int<!>
    get() = y
    set(<!UNUSED_PARAMETER, ACCESSOR_PARAMETER_NAME_SHADOWING!>field<!>) {
        y = field
    }