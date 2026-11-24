import { z } from 'zod';
import { chatLimits } from '../../shared/config.ts';
export const skillsField = z.array(z.uuid()).max(chatLimits.skills).default([]).transform((ids) => [...new Set(ids)]);
export const promptField = z.string().trim().min(1).max(chatLimits.prompt);
