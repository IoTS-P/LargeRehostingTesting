# \<Module Name> \<Module Main Class>

## Category

- <Category 1>
- <Category 2>

## Description

Here is a concise description of the main functions of this module.

## Data and Module Interactions

### Provided API

- 🟠 `<Method Signature>`: Description of the method.
- 🟠 `<Method Signature>`: Description of the method.

### Temporary Data

- 🅘 `<Data Type> <Variable Name>`: Temporary data that needs to be input, explain the variable function here.
- 🅞 `<Data Type> <Variable Name>`: Temporary structure that needs to be output, explain the variable function here.
- 🅘🅞 (optional) `<Data Type> <Variable Name>`: Temporary structure that needs to be input and then output; optional indicates that this module may not use this variable, depending on the module's own logic.

### Configuration File

Configuration file class: `org.someorg.somemodule.somepath.SomeConfig`

```json
{ 
  "some_config_key": "some_config_value", // Description of this configuration item 
  "some_config_key2": 42                  // Description of this configuration item 
}
```

### Module Dependencies

- 🔴 `org.someorg.somemodule.somepath.ClassName1`: The main class in the dependent module, and the function of this dependency.
- 🔴 `org.someorg.somemodule.somepath.ClassName2`: The main class in the dependent module, and the function of this dependency.

### Database Columns

- 🟢 `<column name> <column type>`: Data that this module will output to the database
- 🟢 `<column name> <column type>`: Data that this module will output to the database
- 🟢 `<column name> <column type>`: Data that this module will output to the database
- 🟣 `<view name>`: A view that will be created by this module
  - 🟣 `<column name> <column type>`: Data of this view column
  - 🟣 `<column name> <column type>`: Data of this view column
  - 🟣 `<column name> <column type>`: Data of this view column

## Supplementary Explanation

Here you can use detailed text to explain the functions, operating principles, etc. of this module.

## Notes

Here you need to explain the precautions for using this module.
