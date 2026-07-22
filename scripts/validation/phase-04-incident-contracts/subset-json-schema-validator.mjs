function deepEqual(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function matchesType(value, type) {
  if (type === "null") return value === null;
  if (type === "array") return Array.isArray(value);
  if (type === "object") return typeof value === "object" && value !== null && !Array.isArray(value);
  if (type === "integer") return Number.isInteger(value);
  if (type === "number") return typeof value === "number" && Number.isFinite(value);
  return typeof value === type;
}

function formatMatches(value, format) {
  if (format === "uuid") {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu.test(value);
  }
  if (format === "date-time") {
    return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/u.test(value) &&
      !Number.isNaN(Date.parse(value));
  }
  if (format === "email") return /^[^\s@]+@[^\s@]+\.[^\s@]+$/u.test(value);
  if (format === "uri") {
    try {
      return Boolean(new URL(value).protocol);
    } catch {
      return false;
    }
  }
  if (format === "uri-reference") return !/[\u0000-\u0020]/u.test(value);
  return true;
}

export function createSubsetValidator(resolveLocalReference) {
  function validate(instance, schema, schemaPath, instancePath = "$") {
    if (schema === true) return [];
    if (schema === false) return [`${instancePath}: rejected by schema`];
    const findings = [];
    if (schema.$ref) {
      try {
        const resolved = resolveLocalReference(schemaPath, schema.$ref);
        findings.push(...validate(instance, resolved.target, resolved.targetPath, instancePath));
      } catch {
        findings.push(`${instancePath}: unresolved schema reference`);
      }
    }
    if (schema.type) {
      const allowed = Array.isArray(schema.type) ? schema.type : [schema.type];
      if (!allowed.some((type) => matchesType(instance, type))) {
        findings.push(`${instancePath}: type mismatch`);
        return findings;
      }
    }
    if (schema.enum && !schema.enum.some((candidate) => deepEqual(candidate, instance))) {
      findings.push(`${instancePath}: value is outside enum`);
    }
    if (Object.hasOwn(schema, "const") && !deepEqual(schema.const, instance)) {
      findings.push(`${instancePath}: value does not match const`);
    }
    validateScalar(instance, schema, instancePath, findings);
    validateArray(instance, schema, schemaPath, instancePath, findings);
    validateObject(instance, schema, schemaPath, instancePath, findings);
    for (const child of schema.allOf ?? []) {
      findings.push(...validate(instance, child, schemaPath, instancePath));
    }
    if (schema.oneOf) {
      const matches = schema.oneOf.filter(
        (child) => validate(instance, child, schemaPath, instancePath).length === 0,
      ).length;
      if (matches !== 1) findings.push(`${instancePath}: oneOf match count is not one`);
    }
    if (
      schema.anyOf &&
      !schema.anyOf.some((child) => validate(instance, child, schemaPath, instancePath).length === 0)
    ) {
      findings.push(`${instancePath}: no anyOf branch matched`);
    }
    if (schema.not && validate(instance, schema.not, schemaPath, instancePath).length === 0) {
      findings.push(`${instancePath}: prohibited schema matched`);
    }
    if (schema.if) {
      const matched = validate(instance, schema.if, schemaPath, instancePath).length === 0;
      if (matched && schema.then) findings.push(...validate(instance, schema.then, schemaPath, instancePath));
      if (!matched && schema.else) findings.push(...validate(instance, schema.else, schemaPath, instancePath));
    }
    return findings;
  }

  function validateScalar(instance, schema, instancePath, findings) {
    if (typeof instance === "string") {
      const length = [...instance].length;
      if (Number.isInteger(schema.minLength) && length < schema.minLength) findings.push(`${instancePath}: string is too short`);
      if (Number.isInteger(schema.maxLength) && length > schema.maxLength) findings.push(`${instancePath}: string is too long`);
      if (schema.pattern && !new RegExp(schema.pattern, "u").test(instance)) findings.push(`${instancePath}: string does not match pattern`);
      if (schema.format && !formatMatches(instance, schema.format)) findings.push(`${instancePath}: string does not match format`);
    }
    if (typeof instance === "number") {
      if (typeof schema.minimum === "number" && instance < schema.minimum) findings.push(`${instancePath}: number is below minimum`);
      if (typeof schema.maximum === "number" && instance > schema.maximum) findings.push(`${instancePath}: number is above maximum`);
    }
  }

  function validateArray(instance, schema, schemaPath, instancePath, findings) {
    if (!Array.isArray(instance)) return;
    if (Number.isInteger(schema.minItems) && instance.length < schema.minItems) findings.push(`${instancePath}: array is too short`);
    if (Number.isInteger(schema.maxItems) && instance.length > schema.maxItems) findings.push(`${instancePath}: array is too long`);
    if (schema.uniqueItems && new Set(instance.map((item) => JSON.stringify(item))).size !== instance.length) findings.push(`${instancePath}: array items are not unique`);
    if (schema.items) {
      instance.forEach((item, index) => {
        findings.push(...validate(item, schema.items, schemaPath, `${instancePath}[${index}]`));
      });
    }
  }

  function validateObject(instance, schema, schemaPath, instancePath, findings) {
    if (typeof instance !== "object" || instance === null || Array.isArray(instance)) return;
    const propertyCount = Object.keys(instance).length;
    if (Number.isInteger(schema.minProperties) && propertyCount < schema.minProperties) {
      findings.push(`${instancePath}: object has too few properties`);
    }
    if (Number.isInteger(schema.maxProperties) && propertyCount > schema.maxProperties) {
      findings.push(`${instancePath}: object has too many properties`);
    }
    for (const required of schema.required ?? []) {
      if (!Object.hasOwn(instance, required)) findings.push(`${instancePath}: missing required property`);
    }
    for (const [name, value] of Object.entries(instance)) {
      if (schema.properties && Object.hasOwn(schema.properties, name)) {
        findings.push(...validate(value, schema.properties[name], schemaPath, `${instancePath}.${name}`));
      } else if (schema.additionalProperties === false) {
        findings.push(`${instancePath}: additional property is not allowed`);
      }
    }
  }

  return validate;
}
